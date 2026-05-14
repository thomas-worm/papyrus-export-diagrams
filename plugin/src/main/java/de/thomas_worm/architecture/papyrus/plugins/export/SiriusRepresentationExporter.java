/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.nio.file.Path;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;
import org.eclipse.swt.widgets.Display;

/**
 * Exports every representation contained in a Sirius {@code .aird}
 * file.
 *
 * <p>Sirius's own SVG generator has a cast bug that triggers whenever
 * a representation embeds an {@code SVGFigure} (Papyrus's symbol shape
 * decorator path), so this exporter bypasses
 * {@code DialectUIManager.export} altogether: it opens the session for
 * the refresh side-effects, finds each representation's backing GMF
 * {@link Diagram}, and renders that through the same
 * {@link CopyToImageInvocation} the notation exporter uses.
 */
final class SiriusRepresentationExporter {

    /** Target image format every diagram is rendered as. */
    private final ExportFormat format;

    /** Strategy used to derive each output filename stem. */
    private final FilenameStrategy filenameStrategy;

    /**
     * Snapshot of locally-available fonts, used by the
     * {@link ModelFontRemapper} applied to the session resources.
     */
    private final FontInventory fontInventory;

    /**
     * Post-processor applied to every SVG file after the GMF renderer
     * writes it.
     */
    private final SvgPostProcessor svgPostProcessor;

    /**
     * @param format target image format
     * @param filenameStrategy how to derive filename stems
     * @param fontInventory locally available font families
     * @param svgPostProcessor SVG post-processor to apply after each
     *        successful export
     */
    SiriusRepresentationExporter(ExportFormat format,
                                 FilenameStrategy filenameStrategy,
                                 FontInventory fontInventory,
                                 SvgPostProcessor svgPostProcessor) {
        this.format = format;
        this.filenameStrategy = filenameStrategy;
        this.fontInventory = fontInventory;
        this.svgPostProcessor = svgPostProcessor;
    }

    /**
     * Exports every representation in {@code airdFile} into
     * {@code outputDirectory}, updating {@code counts} as it goes.
     *
     * @param airdFile the {@code .aird} file to read
     * @param outputDirectory directory to write into (must exist)
     * @param counts shared counters updated per representation
     */
    void exportAll(Path airdFile, Path outputDirectory, ExportCounts counts) {
        Optional<CopyToImageInvocation> invocation = CopyToImageInvocation.locate();
        if (invocation.isEmpty()) {
            counts.addFailed(1);
            return;
        }
        IProgressMonitor monitor = new NullProgressMonitor();

        Optional<Session> openedSession = openSession(airdFile, monitor, counts);
        if (openedSession.isEmpty()) return;
        Session session = openedSession.get();
        try {
            Collection<DRepresentationDescriptor> descriptors = enumerateDescriptors(session, airdFile, counts);
            if (descriptors == null) return;
            remapFontsQuietly(session);
            exportEachDescriptor(session, descriptors, invocation.get(), outputDirectory, monitor, counts);
        } finally {
            closeQuietly(session, monitor);
        }
    }

    /**
     * Opens (or reuses) the Sirius session backing {@code airdFile}.
     *
     * @param airdFile the {@code .aird} file to open
     * @param monitor progress monitor
     * @param counts shared counters; incremented on open failure
     * @return the open session, or {@link Optional#empty()} on failure
     */
    private static Optional<Session> openSession(Path airdFile,
                                                 IProgressMonitor monitor,
                                                 ExportCounts counts) {
        URI airdUri = URI.createFileURI(airdFile.toAbsolutePath().toString());
        try {
            Session session = SessionManager.INSTANCE.getSession(airdUri, monitor);
            if (session == null) {
                System.err.println("SiriusRepresentationExporter: SessionManager returned null for " + airdFile);
                counts.addFailed(1);
                return Optional.empty();
            }
            if (!session.isOpen()) {
                session.open(monitor);
            }
            return Optional.of(session);
        } catch (Throwable t) {
            System.err.println(
                    "SiriusRepresentationExporter: failed to open session for "
                            + airdFile + ": " + t);
            counts.addFailed(1);
            return Optional.empty();
        }
    }

    /**
     * Lists every representation descriptor known to the session.
     *
     * @param session open Sirius session
     * @param airdFile path used for log output
     * @param counts shared counters; incremented on enumeration failure
     * @return descriptors, or {@code null} on failure (which is also
     *         tracked in {@code counts})
     */
    private static Collection<DRepresentationDescriptor> enumerateDescriptors(
            Session session, Path airdFile, ExportCounts counts) {
        try {
            return DialectManager.INSTANCE.getAllRepresentationDescriptors(session);
        } catch (Throwable t) {
            System.err.println(
                    "SiriusRepresentationExporter: cannot enumerate representations of "
                            + airdFile + ": " + t);
            counts.addFailed(1);
            return null;
        }
    }

    /**
     * Runs the font remapper over the session's resources, swallowing
     * any failure — font remapping is best-effort cosmetic work.
     *
     * @param session the open session whose resources are remapped
     */
    private void remapFontsQuietly(Session session) {
        try {
            new ModelFontRemapper(fontInventory)
                    .remapResources(session.getTransactionalEditingDomain(),
                            session.getAllSessionResources());
        } catch (Throwable t) {
            System.err.println("SiriusRepresentationExporter: font remap failed (continuing): " + t);
        }
    }

    /**
     * Iterates every descriptor and renders its backing GMF diagram.
     *
     * @param session the open session
     * @param descriptors enumerated representation descriptors
     * @param invocation resolved {@code copyToImage} method
     * @param outputDirectory directory to write into
     * @param monitor progress monitor passed to Sirius's refresh
     * @param counts shared counters updated per descriptor
     */
    private void exportEachDescriptor(Session session,
                                      Collection<DRepresentationDescriptor> descriptors,
                                      CopyToImageInvocation invocation,
                                      Path outputDirectory,
                                      IProgressMonitor monitor,
                                      ExportCounts counts) {
        Map<EObject, Diagram> diagramByRepresentation = collectGmfDiagramsByRepresentation(session);
        Display display = currentOrDefaultDisplay();
        DiagramFilenameGenerator filenames = new DiagramFilenameGenerator(filenameStrategy);

        for (DRepresentationDescriptor descriptor : descriptors) {
            exportOneDescriptor(
                    descriptor, session, diagramByRepresentation,
                    invocation, outputDirectory, filenames, display, monitor, counts);
        }
    }

    /**
     * Renders one descriptor's backing GMF diagram into a file.
     *
     * @param descriptor the descriptor to render
     * @param session the open session
     * @param diagramByRepresentation cached representation→Diagram map
     * @param invocation resolved {@code copyToImage} method
     * @param outputDirectory directory to write into
     * @param filenames shared filename generator
     * @param display SWT display used to marshal the call onto the UI thread
     * @param monitor progress monitor for the refresh call
     * @param counts shared counters
     */
    private void exportOneDescriptor(DRepresentationDescriptor descriptor,
                                     Session session,
                                     Map<EObject, Diagram> diagramByRepresentation,
                                     CopyToImageInvocation invocation,
                                     Path outputDirectory,
                                     DiagramFilenameGenerator filenames,
                                     Display display,
                                     IProgressMonitor monitor,
                                     ExportCounts counts) {
        DRepresentation representation = resolveRepresentation(descriptor, counts);
        if (representation == null) return;

        refreshRepresentationQuietly(session, representation, descriptor, monitor);

        Diagram diagram = diagramByRepresentation.get(representation);
        if (diagram == null) {
            diagramByRepresentation = collectGmfDiagramsByRepresentation(session);
            diagram = diagramByRepresentation.get(representation);
        }
        if (diagram == null) {
            System.err.println("SiriusRepresentationExporter: no backing GMF Diagram for "
                    + describe(descriptor) + " — skipping");
            counts.addFailed(1);
            return;
        }

        runExportOnUiThread(display, diagram, descriptor, filenames, outputDirectory, invocation, counts);
    }

    /**
     * Lazily resolves a descriptor's {@code DRepresentation}.
     *
     * @param descriptor the descriptor to resolve
     * @param counts counters; incremented on resolution failure
     * @return the resolved representation, or {@code null} on failure
     */
    private static DRepresentation resolveRepresentation(DRepresentationDescriptor descriptor,
                                                         ExportCounts counts) {
        try {
            return descriptor.getRepresentation();
        } catch (Throwable t) {
            System.err.println(
                    "SiriusRepresentationExporter: cannot resolve representation for "
                            + describe(descriptor) + ": " + t);
            counts.addFailed(1);
            return null;
        }
    }

    /**
     * Asks Sirius to refresh the representation so the backing GMF
     * diagram reflects the current semantic model. Failures are
     * tolerated; we fall back to whatever's already in the
     * {@code .aird}.
     *
     * <p>{@code DialectManager.refresh} mutates the model, so the
     * call is wrapped in a {@link RecordingCommand} on the session's
     * editing domain when one is available.
     *
     * @param session the open session
     * @param representation the representation to refresh
     * @param descriptor descriptor used only for log output
     * @param monitor progress monitor
     */
    private static void refreshRepresentationQuietly(Session session,
                                                     DRepresentation representation,
                                                     DRepresentationDescriptor descriptor,
                                                     IProgressMonitor monitor) {
        try {
            TransactionalEditingDomain editingDomain = session.getTransactionalEditingDomain();
            if (editingDomain != null) {
                editingDomain.getCommandStack().execute(
                        new RecordingCommand(editingDomain, "Refresh representation") {
                            /**
                             * Runs Sirius's refresh inside the command's
                             * write transaction; refresh mutates the
                             * model and would otherwise trip the
                             * transactional-resource-set guard.
                             */
                            @Override
                            protected void doExecute() {
                                DialectManager.INSTANCE.refresh(representation, monitor);
                            }
                        });
            } else {
                DialectManager.INSTANCE.refresh(representation, monitor);
            }
        } catch (Throwable t) {
            System.err.println(
                    "SiriusRepresentationExporter: refresh failed for "
                            + describe(descriptor) + " (continuing with stale diagram): " + t);
        }
    }

    /**
     * Runs {@link CopyToImageInvocation#exportDiagram} and the SVG
     * post-process on the SWT UI thread.
     *
     * <p>GMF's render path creates Shells and uses the {@link Display}
     * directly, so the call must run on whatever thread owns the
     * display. {@link Display#syncExec} blocks until the render
     * completes.
     *
     * @param display the SWT display to marshal onto
     * @param diagram the backing GMF diagram to render
     * @param descriptor descriptor used to derive the file name
     * @param filenames shared filename generator
     * @param outputDirectory directory to write into
     * @param invocation resolved {@code copyToImage} method
     * @param counts shared counters
     */
    private void runExportOnUiThread(Display display,
                                     Diagram diagram,
                                     DRepresentationDescriptor descriptor,
                                     DiagramFilenameGenerator filenames,
                                     Path outputDirectory,
                                     CopyToImageInvocation invocation,
                                     ExportCounts counts) {
        String stem = filenames.stemFor(descriptor);
        Path outputFile = outputDirectory.resolve(stem + "." + format.fileExtension());

        display.syncExec(() -> {
            try {
                invocation.exportDiagram(diagram, outputFile, format.toGmfFormat());
                svgPostProcessor.process(outputFile);
                System.out.println("exported (Sirius/GMF): " + outputFile);
                counts.addExported(1);
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                System.err.println(
                        "SiriusRepresentationExporter: failed to export " + stem + ": " + cause);
                cause.printStackTrace(System.err);
                counts.addFailed(1);
            }
        });
    }

    /**
     * Builds a lookup table from each {@code DRepresentation} to its
     * backing GMF {@code Diagram} by walking every loaded session
     * resource.
     *
     * @param session the open session
     * @return identity-keyed map of representation→diagram
     */
    private static Map<EObject, Diagram> collectGmfDiagramsByRepresentation(Session session) {
        Map<EObject, Diagram> diagramByRepresentation = new IdentityHashMap<>();
        for (Resource resource : session.getAllSessionResources()) {
            indexDiagramsByRepresentation(resource, diagramByRepresentation);
        }
        try {
            Resource sessionResource = session.getSessionResource();
            if (sessionResource != null) {
                indexDiagramsByRepresentation(sessionResource, diagramByRepresentation);
            }
        } catch (Throwable ignored) {
        }
        return diagramByRepresentation;
    }

    /**
     * Walks {@code resource} and indexes every reachable
     * {@link Diagram} keyed by its semantic element.
     *
     * @param resource resource to walk
     * @param target identity map populated with the findings
     */
    private static void indexDiagramsByRepresentation(Resource resource,
                                                      Map<EObject, Diagram> target) {
        for (Iterator<EObject> iterator = resource.getAllContents(); iterator.hasNext(); ) {
            EObject element = iterator.next();
            if (element instanceof Diagram diagram) {
                EObject representation = diagram.getElement();
                if (representation != null) {
                    target.putIfAbsent(representation, diagram);
                }
            }
        }
    }

    /**
     * @return the {@link Display} bound to the current thread when
     *         present, otherwise the shared default display
     */
    private static Display currentOrDefaultDisplay() {
        Display current = Display.getCurrent();
        return current != null ? current : Display.getDefault();
    }

    /**
     * Closes the Sirius session, swallowing failures.
     *
     * @param session the session to close
     * @param monitor progress monitor
     */
    private static void closeQuietly(Session session, IProgressMonitor monitor) {
        try { session.close(monitor); } catch (Throwable ignored) { }
    }

    /**
     * @param descriptor a representation descriptor
     * @return the descriptor's user-facing name, or
     *         {@code "<unnamed>"} when blank
     */
    private static String describe(DRepresentationDescriptor descriptor) {
        String name = descriptor.getName();
        return (name == null || name.isBlank()) ? "<unnamed>" : name;
    }
}
