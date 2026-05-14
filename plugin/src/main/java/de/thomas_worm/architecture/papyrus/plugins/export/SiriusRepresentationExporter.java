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

    private final ExportFormat format;
    private final FilenameStrategy filenameStrategy;
    private final FontInventory fontInventory;
    private final SvgPostProcessor svgPostProcessor;

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

    private void remapFontsQuietly(Session session) {
        try {
            new ModelFontRemapper(fontInventory)
                    .remapResources(session.getTransactionalEditingDomain(),
                            session.getAllSessionResources());
        } catch (Throwable t) {
            System.err.println("SiriusRepresentationExporter: font remap failed (continuing): " + t);
        }
    }

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

    private static void refreshRepresentationQuietly(Session session,
                                                     DRepresentation representation,
                                                     DRepresentationDescriptor descriptor,
                                                     IProgressMonitor monitor) {
        try {
            TransactionalEditingDomain editingDomain = session.getTransactionalEditingDomain();
            if (editingDomain != null) {
                editingDomain.getCommandStack().execute(
                        new RecordingCommand(editingDomain, "Refresh representation") {
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

    private static Display currentOrDefaultDisplay() {
        Display current = Display.getCurrent();
        return current != null ? current : Display.getDefault();
    }

    private static void closeQuietly(Session session, IProgressMonitor monitor) {
        try { session.close(monitor); } catch (Throwable ignored) { }
    }

    private static String describe(DRepresentationDescriptor descriptor) {
        String name = descriptor.getName();
        return (name == null || name.isBlank()) ? "<unnamed>" : name;
    }
}
