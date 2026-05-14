/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
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

    /**
     * Snapshot of the font families AWT sees and the chosen substitute
     * family used to overwrite unavailable ones.
     */
    private final FontInventory fontInventory;

    /**
     * How many {@link FontStyle} entries have been rewritten so far in
     * the current {@link #remapResources} invocation. Incremented
     * lazily from inside the transaction body so the final value is
     * available for logging once the command commits.
     */
    private int rewriteCount;

    /**
     * Creates a remapper bound to the given font inventory.
     *
     * @param fontInventory snapshot of locally-available fonts and the
     *        chosen substitute family
     */
    ModelFontRemapper(FontInventory fontInventory) {
        this.fontInventory = fontInventory;
    }

    /**
     * Remaps all {@link FontStyle} entries reachable from
     * {@code resources}, writing inside a transaction on
     * {@code editingDomain}. If {@code editingDomain} is {@code null}
     * mutations are applied directly.
     *
     * @param editingDomain editing domain owning the resources, or
     *        {@code null} for direct (non-transactional) writes
     * @param resources every resource to scan
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
                        /**
                         * Runs the remap inside the command's write
                         * transaction so the resource set accepts the
                         * mutations.
                         */
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

    /**
     * Walks one resource's contents and remaps every reachable
     * {@link FontStyle}.
     *
     * @param resource the resource to traverse
     */
    private void remapOne(Resource resource) {
        Iterator<EObject> iterator = resource.getAllContents();
        while (iterator.hasNext()) {
            EObject element = iterator.next();
            if (element instanceof FontStyle fontStyle) {
                considerFontStyle(fontStyle);
            }
        }
    }

    /**
     * Inspects a single {@link FontStyle} and rewrites its
     * {@code fontName} if needed.
     *
     * @param fontStyle the style element to consider
     */
    private void considerFontStyle(FontStyle fontStyle) {
        String name = fontStyle.getFontName();
        if (name == null || name.isBlank()) return;
        if (!shouldRewrite(fontStyle, name)) return;
        fontStyle.setFontName(fontInventory.substituteFamily());
        rewriteCount++;
    }

    /**
     * Decides whether a particular {@link FontStyle} should be
     * rewritten. Returns {@code true} when the current font is
     * unavailable locally, and additionally on non-macOS when the
     * font name is the implicit GMF/Papyrus platform default rather
     * than an explicit author choice.
     *
     * @param fontStyle the style being considered
     * @param currentName the font name currently set
     * @return {@code true} to rewrite to the substitute family
     */
    private boolean shouldRewrite(FontStyle fontStyle, String currentName) {
        if (!fontInventory.isAvailable(currentName)) return true;
        if (fontInventory.runningOnMacOs()) return false;
        if (isExplicitlySet(fontStyle)) return false;
        return !currentName.equals(fontInventory.substituteFamily());
    }

    /**
     * @param fontStyle the FontStyle to introspect
     * @return whether the {@code fontName} attribute was explicitly
     *         set in the source notation (as opposed to defaulting
     *         from the EMF metamodel)
     */
    private static boolean isExplicitlySet(FontStyle fontStyle) {
        return fontStyle.eIsSet(NotationPackage.eINSTANCE.getFontStyle_FontName());
    }

    /**
     * Writes a single summary line to {@code stdout} describing what
     * the remap did (or that no remap was needed).
     */
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
