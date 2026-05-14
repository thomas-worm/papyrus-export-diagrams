/*
 * Copyright (c) 2026 Thomas Worm
 * SPDX-License-Identifier: MIT
 */
package de.thomas_worm.architecture.papyrus.plugins.export;

import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * Activates the OSGi bundles that the headless export pipeline depends
 * on.
 *
 * <p>Activation is best-effort: bundles that are missing from the
 * install (e.g. a Papyrus-Classic distribution without Sirius) are
 * logged to stderr but do not fail activation. The list is ordered so
 * dependencies activate before their consumers — the CSS bundles must
 * be up before any {@code .notation} is loaded so the resource factory
 * override produces CSS-aware diagrams.
 */
final class PapyrusBundleActivator {

    /**
     * Symbolic names of every OSGi bundle the pipeline expects to be
     * started before any export runs. Bundles that are absent from the
     * install are skipped silently.
     */
    private static final List<String> REQUIRED_BUNDLES = List.of(
            "org.eclipse.papyrus.infra.core",
            "org.eclipse.papyrus.infra.gmfdiag.common",
            "org.eclipse.papyrus.infra.gmfdiag.css",
            "org.eclipse.papyrus.uml.diagram.css",
            "org.eclipse.papyrus.infra.gmfdiag.style",
            "org.eclipse.gmf.runtime.diagram.ui.render",
            "org.eclipse.sirius",
            "org.eclipse.sirius.ui",
            "org.eclipse.sirius.diagram.ui");

    /** Utility class; not instantiable. */
    private PapyrusBundleActivator() {
    }

    /**
     * Activates every required bundle in declaration order. Bundles
     * that aren't installed locally are reported on stderr without
     * failing the call.
     */
    static void activateAll() {
        for (String bundleSymbolicName : REQUIRED_BUNDLES) {
            activate(bundleSymbolicName);
        }
    }

    /**
     * Starts a single OSGi bundle if it's installed; silent on absence.
     *
     * @param symbolicName the bundle's
     *        {@code Bundle-SymbolicName} as exposed by
     *        {@link Platform#getBundle(String)}
     */
    private static void activate(String symbolicName) {
        try {
            Bundle bundle = Platform.getBundle(symbolicName);
            if (bundle != null) bundle.start();
        } catch (Throwable t) {
            System.err.println("Could not activate " + symbolicName + ": " + t);
        }
    }
}
