package de.thomas_worm.architecture.papyrus.plugins.export;

import java.util.Iterator;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.notation.FontStyle;
import org.eclipse.gmf.runtime.notation.NotationPackage;

/**
 * Walks every loaded notation {@link FontStyle} and substitutes any
 * font name that the local JVM can't render.
 *
 * <p>The remap is in-memory only — the source files are never touched.
 * Mutations are wrapped in a {@link RecordingCommand} so the
 * transactional resource set accepts the writes.
 *
 * <p>On non-macOS hosts, {@link FontStyle} entries that weren't
 * explicitly authored (i.e. {@code eIsSet} returns {@code false} for
 * {@code fontName}) are also rewritten to the substitute family. Those
 * implicit entries otherwise inherit GMF's platform default
 * (Tahoma / Segoe UI / etc.) and the rendered SVG diverges from how the
 * same model looks on macOS.
 */
final class ModelFontRemapper {

    private final FontInventory fontInventory;
    private int rewriteCount;

    ModelFontRemapper(FontInventory fontInventory) {
        this.fontInventory = fontInventory;
    }

    /**
     * Remaps all {@link FontStyle} entries reachable from
     * {@code resources}, writing inside a transaction on
     * {@code editingDomain}. If {@code editingDomain} is {@code null}
     * mutations are applied directly.
     *
     * @return the number of {@link FontStyle} entries that were
     *         actually rewritten
     */
    int remapResources(TransactionalEditingDomain editingDomain,
                       Iterable<? extends Resource> resources) {
        Runnable remapAll = () -> {
            for (Resource resource : resources) {
                remapOne(resource);
            }
        };
        if (editingDomain != null) {
            editingDomain.getCommandStack().execute(
                    new RecordingCommand(editingDomain, "Remap fonts") {
                        @Override
                        protected void doExecute() {
                            remapAll.run();
                        }
                    });
        } else {
            remapAll.run();
        }
        logRemapSummary();
        return rewriteCount;
    }

    private void remapOne(Resource resource) {
        Iterator<EObject> iterator = resource.getAllContents();
        while (iterator.hasNext()) {
            EObject element = iterator.next();
            if (element instanceof FontStyle fontStyle) {
                considerFontStyle(fontStyle);
            }
        }
    }

    private void considerFontStyle(FontStyle fontStyle) {
        String name = fontStyle.getFontName();
        if (name == null || name.isBlank()) return;
        if (!shouldRewrite(fontStyle, name)) return;
        fontStyle.setFontName(fontInventory.substituteFamily());
        rewriteCount++;
    }

    private boolean shouldRewrite(FontStyle fontStyle, String currentName) {
        if (!fontInventory.isAvailable(currentName)) return true;
        if (fontInventory.runningOnMacOs()) return false;
        if (isExplicitlySet(fontStyle)) return false;
        return !currentName.equals(fontInventory.substituteFamily());
    }

    private static boolean isExplicitlySet(FontStyle fontStyle) {
        return fontStyle.eIsSet(NotationPackage.eINSTANCE.getFontStyle_FontName());
    }

    private void logRemapSummary() {
        if (rewriteCount > 0) {
            System.out.println(
                    "ModelFontRemapper: " + rewriteCount
                            + " FontStyle entries referenced fonts not installed locally "
                            + "(of " + fontInventory.availableCount() + " families available); "
                            + "rewrote them to '" + fontInventory.substituteFamily() + "'");
        } else {
            System.out.println(
                    "ModelFontRemapper: all model FontStyle entries reference fonts "
                            + "already installed locally ("
                            + fontInventory.availableCount() + " families available); "
                            + "no remapping needed");
        }
    }
}
