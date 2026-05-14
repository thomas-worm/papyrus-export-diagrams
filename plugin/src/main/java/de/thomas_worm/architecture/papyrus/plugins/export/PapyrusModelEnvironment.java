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

    private final ServicesRegistry servicesRegistry;
    private final ModelSet modelSet;
    private final TransactionalEditingDomain editingDomain;

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

    ModelSet modelSet() {
        return modelSet;
    }

    TransactionalEditingDomain editingDomain() {
        return editingDomain;
    }

    @Override
    public void close() {
        try { servicesRegistry.disposeRegistry(); } catch (Throwable ignored) { }
        try { editingDomain.dispose(); } catch (Throwable ignored) { }
    }

    private static void registerNoOpStubs(ServicesRegistry registry) {
        registerNoOpMultiDiagramEditor(registry);
        registerNoOpLabelProviderService(registry);
    }

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
