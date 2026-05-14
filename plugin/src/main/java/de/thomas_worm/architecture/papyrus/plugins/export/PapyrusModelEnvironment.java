/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.papyrus.infra.core.resource.ModelSet;
import org.eclipse.papyrus.infra.core.services.ServicesRegistry;

/**
 * The minimal Papyrus runtime environment needed to render a notation
 * diagram outside of an editor.
 *
 * <p>Wires up:
 * <ul>
 *   <li>a fresh {@link ModelSet} with a {@link TransactionalEditingDomain}
 *       so {@code TransactionUtil.getEditingDomain(diagram)} can resolve
 *       it,</li>
 *   <li>a {@link ServicesRegistry} that the loaded diagram's CSS
 *       refresh chain consults,</li>
 *   <li>no-op stubs for the {@code IMultiDiagramEditor} and
 *       {@code LabelProviderService} entries the Papyrus edit parts
 *       expect to find (without them, {@code ServiceNotFoundException}s
 *       and {@code NullPointerException}s are logged and rendering
 *       skips paths it shouldn't),</li>
 *   <li>{@code CSSHelper.installCSSSupport(modelSet)} so loaded
 *       {@code .notation} resources are wrapped in
 *       {@code CSSNotationResource} and the CSS engine actually applies
 *       the active theme.</li>
 * </ul>
 *
 * <p>The instance owns the {@link ModelSet} / {@link ServicesRegistry}
 * / {@link TransactionalEditingDomain} lifecycle and disposes them on
 * {@link #close()}.
 */
final class PapyrusModelEnvironment implements AutoCloseable {

    /** Papyrus services registry that owns the {@link ModelSet}. */
    private final ServicesRegistry servicesRegistry;

    /**
     * The CSS-aware {@link ModelSet} that Papyrus's GMF edit parts
     * resolve services from at refresh time.
     */
    private final ModelSet modelSet;

    /**
     * The transactional editing domain bound to {@link #modelSet}.
     * Edits to the loaded notation tree go through this domain.
     */
    private final TransactionalEditingDomain editingDomain;

    /**
     * Private constructor; instances are built by {@link #create()}.
     *
     * @param servicesRegistry the populated services registry
     * @param modelSet the prepared model set
     * @param editingDomain the editing domain bound to {@code modelSet}
     */
    private PapyrusModelEnvironment(ServicesRegistry servicesRegistry,
                                    ModelSet modelSet,
                                    TransactionalEditingDomain editingDomain) {
        this.servicesRegistry = servicesRegistry;
        this.modelSet = modelSet;
        this.editingDomain = editingDomain;
    }

    /**
     * Creates a fully-wired environment ready to load Papyrus
     * {@code .di}/{@code .notation}/{@code .uml} triples.
     *
     * @return the new environment instance
     * @throws RuntimeException when Papyrus's services registry can't
     *         be started
     */
    static PapyrusModelEnvironment create() {
        ServicesRegistry registry = new ServicesRegistry();
        ModelSet modelSet = new ModelSet();
        TransactionalEditingDomain editingDomain = TransactionalEditingDomain.Factory
                .INSTANCE
                .createEditingDomain(modelSet);
        registry.add(ModelSet.class, 10, modelSet);
        registerNoOpStubs(registry);
        try {
            registry.startRegistry();
        } catch (Throwable t) {
            throw new RuntimeException("Could not start Papyrus services registry", t);
        }
        installCssSupport(modelSet);
        return new PapyrusModelEnvironment(registry, modelSet, editingDomain);
    }

    /** @return the {@link ModelSet} owned by this environment. */
    ModelSet modelSet() {
        return modelSet;
    }

    /** @return the {@link TransactionalEditingDomain} bound to the model set. */
    TransactionalEditingDomain editingDomain() {
        return editingDomain;
    }

    /**
     * Disposes the services registry and the editing domain in that
     * order. Errors during disposal are swallowed because the caller
     * usually wants to move on to the next diagram anyway.
     */
    @Override
    public void close() {
        try { servicesRegistry.disposeRegistry(); } catch (Throwable ignored) { }
        try { editingDomain.dispose(); } catch (Throwable ignored) { }
    }

    /**
     * Adds the no-op proxies for the services Papyrus's edit parts
     * look up during rendering.
     *
     * @param registry the registry to populate
     */
    private static void registerNoOpStubs(ServicesRegistry registry) {
        registerNoOpMultiDiagramEditor(registry);
        registerNoOpLabelProviderService(registry);
    }

    /**
     * Invokes {@code CSSHelper.installCSSSupport(modelSet)} reflectively
     * so that the loaded {@code .notation} resources are wrapped in
     * {@code CSSNotationResource} and the CSS engine fires during
     * paint. Silently no-ops if the CSS helper bundle isn't present.
     *
     * @param modelSet the model set to enable CSS support on
     */
    private static void installCssSupport(ModelSet modelSet) {
        try {
            Class<?> cssHelperClass = Class.forName(
                    "org.eclipse.papyrus.infra.gmfdiag.css.helper.CSSHelper");
            Method installMethod = findInstallCssSupportMethod(cssHelperClass, modelSet);
            if (installMethod == null) {
                System.err.println("PapyrusModelEnvironment: CSSHelper.installCSSSupport not found");
                return;
            }
            installMethod.invoke(null, modelSet);
        } catch (ClassNotFoundException e) {
            System.err.println(
                    "PapyrusModelEnvironment: CSS helper bundle not present; gradients disabled");
        } catch (Throwable t) {
            System.err.println("PapyrusModelEnvironment: CSS support installation failed: " + t);
        }
    }

    /**
     * Resolves the right {@code installCSSSupport} overload on
     * {@code CSSHelper}. The lookup prefers a method whose parameter
     * type is assignable from {@link ModelSet} (i.e. a {@code ModelSet}
     * or supertype that {@code ModelSet} extends).
     *
     * @param cssHelperClass the {@code CSSHelper} class
     * @param modelSet the model set whose runtime class is needed
     * @return the resolved method, or {@code null} when none matches
     */
    private static Method findInstallCssSupportMethod(Class<?> cssHelperClass, ModelSet modelSet) {
        Method bestMatch = null;
        for (Method method : cssHelperClass.getMethods()) {
            if (!"installCSSSupport".equals(method.getName())) continue;
            if (method.getParameterCount() != 1) continue;
            bestMatch = method;
            if (method.getParameterTypes()[0].isAssignableFrom(modelSet.getClass())) {
                return method;
            }
        }
        return bestMatch;
    }

    /**
     * Registers a {@link Proxy}-backed no-op stub for Papyrus's
     * {@code IMultiDiagramEditor} service so refresh-time lookups
     * succeed without logging a {@code ServiceNotFoundException}.
     *
     * @param registry the registry to add the stub to
     */
    @SuppressWarnings("unchecked")
    private static void registerNoOpMultiDiagramEditor(ServicesRegistry registry) {
        try {
            Class<?> editorInterface = Class.forName(
                    "org.eclipse.papyrus.infra.ui.editor.IMultiDiagramEditor");
            InvocationHandler handler = (proxy, method, args) -> {
                if ("getServicesRegistry".equals(method.getName())) return registry;
                return defaultValueFor(method.getReturnType());
            };
            Object stub = Proxy.newProxyInstance(
                    editorInterface.getClassLoader(),
                    new Class<?>[] { editorInterface },
                    handler);
            registry.add((Class<Object>) editorInterface, 1, stub);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            System.err.println(
                    "PapyrusModelEnvironment: failed to register IMultiDiagramEditor stub: " + t);
        }
    }

    /**
     * Registers a {@link Proxy}-backed no-op stub for Papyrus's
     * {@code LabelProviderService}. Without it,
     * {@code DiagramEditPartsUtil.getIcon} dereferences a null label
     * provider during paint and the export fails for every diagram.
     *
     * @param registry the registry to add the stub to
     */
    @SuppressWarnings("unchecked")
    private static void registerNoOpLabelProviderService(ServicesRegistry registry) {
        try {
            Class<?> labelProviderServiceClass = Class.forName(
                    "org.eclipse.papyrus.infra.services.labelprovider.service.LabelProviderService");
            Class<?> labelProviderInterface = Class.forName("org.eclipse.jface.viewers.ILabelProvider");
            Object labelProviderStub = createLabelProviderStub(labelProviderInterface);
            Object serviceStub = createLabelProviderServiceStub(
                    labelProviderServiceClass, labelProviderInterface, labelProviderStub);
            registry.add((Class<Object>) labelProviderServiceClass, 1, serviceStub);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            System.err.println(
                    "PapyrusModelEnvironment: failed to register LabelProviderService stub: " + t);
        }
    }

    /**
     * Builds a {@link Proxy}-backed {@code ILabelProvider} that
     * returns the element's {@code toString} for {@code getText} and
     * the type's zero value for everything else.
     *
     * @param labelProviderInterface the {@code ILabelProvider} class
     * @return the proxy stub instance
     */
    private static Object createLabelProviderStub(Class<?> labelProviderInterface) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getText".equals(method.getName()) && args != null && args.length == 1) {
                return args[0] == null ? "" : String.valueOf(args[0]);
            }
            return defaultValueFor(method.getReturnType());
        };
        return Proxy.newProxyInstance(
                labelProviderInterface.getClassLoader(),
                new Class<?>[] { labelProviderInterface },
                handler);
    }

    /**
     * Builds a {@link Proxy}-backed {@code LabelProviderService} that
     * returns {@code labelProviderStub} for every
     * {@code getLabelProvider*} call.
     *
     * @param labelProviderServiceClass the service interface
     * @param labelProviderInterface the {@code ILabelProvider} interface
     * @param labelProviderStub the stub returned to every caller
     * @return the proxy service stub
     */
    private static Object createLabelProviderServiceStub(Class<?> labelProviderServiceClass,
                                                         Class<?> labelProviderInterface,
                                                         Object labelProviderStub) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().startsWith("getLabelProvider")
                    && labelProviderInterface.isAssignableFrom(method.getReturnType())) {
                return labelProviderStub;
            }
            return defaultValueFor(method.getReturnType());
        };
        return Proxy.newProxyInstance(
                labelProviderServiceClass.getClassLoader(),
                new Class<?>[] { labelProviderServiceClass },
                handler);
    }

    /**
     * Default value used by the proxy stubs for any method whose
     * return type isn't handled explicitly.
     *
     * @param returnType the method's declared return type
     * @return {@code 0} for primitive numerics, {@code false} for
     *         {@code boolean}, {@code '\0'} for {@code char},
     *         {@code null} for everything else
     */
    private static Object defaultValueFor(Class<?> returnType) {
        if (returnType == boolean.class) return Boolean.FALSE;
        if (returnType == byte.class)    return (byte) 0;
        if (returnType == short.class)   return (short) 0;
        if (returnType == int.class)     return 0;
        if (returnType == long.class)    return 0L;
        if (returnType == float.class)   return 0f;
        if (returnType == double.class)  return 0d;
        if (returnType == char.class)    return '\0';
        return null;
    }
}
