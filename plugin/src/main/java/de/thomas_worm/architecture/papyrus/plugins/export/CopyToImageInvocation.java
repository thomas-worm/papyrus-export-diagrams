/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
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

    /**
     * The reflectively-resolved {@code copyToImage} method.
     * Immutable after construction.
     */
    private final Method copyToImageMethod;

    /**
     * Wraps the method handle so callers don't need to deal with
     * reflection.
     *
     * @param copyToImageMethod the resolved method
     */
    private CopyToImageInvocation(Method copyToImageMethod) {
        this.copyToImageMethod = copyToImageMethod;
    }

    /**
     * Returns an invocation handle wrapping whichever copyToImage
     * overload was found on the classpath, or {@link Optional#empty()}
     * if neither is present.
     *
     * @return optional invocation handle
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
     * Writes {@code diagram} to {@code outputFile} in the given
     * format.
     *
     * @param diagram the diagram to render
     * @param outputFile path of the file to produce
     * @param format GMF runtime format identifier
     * @throws Exception when the reflective invocation throws; the
     *         exception is rethrown as-is after unwrapping
     *         {@code InvocationTargetException}
     */
    void exportDiagram(Diagram diagram, Path outputFile, ImageFileFormat format) throws Exception {
        IPath eclipsePath = org.eclipse.core.runtime.Path.fromOSString(outputFile.toString());
        Object[] arguments = buildArguments(diagram, eclipsePath, format);
        invoke(arguments);
    }

    /**
     * Builds the argument array for whichever overload was resolved.
     *
     * @param diagram diagram instance
     * @param outputPath Eclipse-style absolute target path
     * @param format GMF format identifier
     * @return arguments in the order required by the resolved method
     */
    private Object[] buildArguments(Diagram diagram, IPath outputPath, ImageFileFormat format) {
        if (copyToImageMethod.getParameterCount() == 5) {
            return new Object[] {
                    diagram, outputPath, format,
                    new NullProgressMonitor(), PreferencesHint.USE_DEFAULTS };
        }
        return new Object[] {
                diagram, outputPath, format, new NullProgressMonitor() };
    }

    /**
     * Invokes the resolved method either statically or on a fresh
     * {@link CopyToImageUtil} instance, depending on its modifiers.
     *
     * @param arguments the prepared argument array
     * @throws Exception when the reflective invocation throws
     */
    private void invoke(Object[] arguments) throws Exception {
        if (Modifier.isStatic(copyToImageMethod.getModifiers())) {
            copyToImageMethod.invoke(null, arguments);
        } else {
            copyToImageMethod.invoke(new CopyToImageUtil(), arguments);
        }
    }

    /**
     * Searches {@link CopyToImageUtil#getMethods()} for the 4- or
     * 5-argument {@code copyToImage(Diagram, …)} overload. The
     * 5-argument form is preferred when both are present.
     *
     * @return the best-matching method, or {@code null} if neither
     *         shape is on the classpath
     */
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

    /**
     * Prints every {@code copyToImage} method found on
     * {@link CopyToImageUtil} to stderr — used as a diagnostic when
     * the search above found nothing usable.
     */
    private static void logAvailableOverloads() {
        System.err.println("CopyToImageInvocation: available copyToImage overloads:");
        for (Method method : CopyToImageUtil.class.getMethods()) {
            if ("copyToImage".equals(method.getName())) {
                System.err.println("  - " + method);
            }
        }
    }
}
