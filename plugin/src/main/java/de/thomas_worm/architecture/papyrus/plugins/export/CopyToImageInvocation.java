package de.thomas_worm.architecture.papyrus.plugins.export;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat;
import org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil;
import org.eclipse.gmf.runtime.notation.Diagram;

/**
 * Locates the appropriate
 * {@link CopyToImageUtil#copyToImage} overload on the classpath and
 * provides a convenient invocation surface.
 *
 * <p>Different GMF releases ship the call as either a 4-argument
 * ({@code Diagram, IPath, ImageFileFormat, IProgressMonitor}) or a
 * 5-argument variant that adds a trailing {@link PreferencesHint}. The
 * lookup prefers the 5-argument variant because it lets us pass
 * {@link PreferencesHint#USE_DEFAULTS} explicitly — necessary for the
 * GMF renderer to initialise styling preferences outside of an active
 * workbench instance.
 *
 * <p>Overloads whose first parameter is a {@code DiagramEditPart} are
 * skipped: they expect a pre-built edit part that headless callers
 * don't have.
 */
final class CopyToImageInvocation {

    private final Method copyToImageMethod;

    private CopyToImageInvocation(Method copyToImageMethod) {
        this.copyToImageMethod = copyToImageMethod;
    }

    /**
     * Returns an invocation handle wrapping whichever copyToImage
     * overload was found on the classpath, or {@link Optional#empty()}
     * if neither is present.
     */
    static Optional<CopyToImageInvocation> locate() {
        Method match = findDiagramCopyToImage();
        if (match != null) {
            System.out.println("CopyToImageInvocation: using " + match);
        } else {
            System.err.println(
                    "CopyToImageInvocation: no compatible CopyToImageUtil.copyToImage(Diagram, ...) found.");
            logAvailableOverloads();
        }
        return Optional.ofNullable(match).map(CopyToImageInvocation::new);
    }

    /**
     * Writes {@code diagram} to {@code outputFile} in the given format.
     *
     * @throws Exception when the reflective invocation throws, the
     *         exception is rethrown as-is (after unwrapping
     *         {@code InvocationTargetException})
     */
    void exportDiagram(Diagram diagram, Path outputFile, ImageFileFormat format) throws Exception {
        IPath eclipsePath = org.eclipse.core.runtime.Path.fromOSString(outputFile.toString());
        Object[] arguments = buildArguments(diagram, eclipsePath, format);
        invoke(arguments);
    }

    private Object[] buildArguments(Diagram diagram, IPath outputPath, ImageFileFormat format) {
        if (copyToImageMethod.getParameterCount() == 5) {
            return new Object[] {
                    diagram, outputPath, format,
                    new NullProgressMonitor(), PreferencesHint.USE_DEFAULTS };
        }
        return new Object[] {
                diagram, outputPath, format, new NullProgressMonitor() };
    }

    private void invoke(Object[] arguments) throws Exception {
        if (Modifier.isStatic(copyToImageMethod.getModifiers())) {
            copyToImageMethod.invoke(null, arguments);
        } else {
            copyToImageMethod.invoke(new CopyToImageUtil(), arguments);
        }
    }

    private static Method findDiagramCopyToImage() {
        Method fourArgMatch = null;
        Method fiveArgMatch = null;
        for (Method candidate : CopyToImageUtil.class.getMethods()) {
            if (!"copyToImage".equals(candidate.getName())) continue;
            int parameterCount = candidate.getParameterCount();
            if (parameterCount != 4 && parameterCount != 5) continue;
            Class<?>[] parameters = candidate.getParameterTypes();
            if (!Diagram.class.isAssignableFrom(parameters[0])) continue;
            candidate.setAccessible(true);
            if (parameterCount == 5) {
                fiveArgMatch = candidate;
            } else {
                fourArgMatch = candidate;
            }
        }
        return fiveArgMatch != null ? fiveArgMatch : fourArgMatch;
    }

    private static void logAvailableOverloads() {
        System.err.println("CopyToImageInvocation: available copyToImage overloads:");
        for (Method method : CopyToImageUtil.class.getMethods()) {
            if ("copyToImage".equals(method.getName())) {
                System.err.println("  - " + method);
            }
        }
    }
}
