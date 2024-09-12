/*******************************************************************************
 * Copyright (c) 2024 École Polytechnique de Montréal
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License 2.0 which
 * accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.tracecompass.incubator.rocm.core.exatracer.trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.analysis.os.linux.core.event.aspect.LinuxPidAspect;
import org.eclipse.tracecompass.analysis.os.linux.core.event.aspect.LinuxTidAspect;
import org.eclipse.tracecompass.incubator.gpu.core.trace.IGpuTrace;
import org.eclipse.tracecompass.incubator.gpu.core.trace.IGpuTraceEventLayout;
import org.eclipse.tracecompass.incubator.internal.rocm.core.Activator;
import org.eclipse.tracecompass.incubator.rocm.core.trace.RocmTrace;
import org.eclipse.tracecompass.tmf.core.event.ITmfEvent;
import org.eclipse.tracecompass.tmf.core.event.aspect.ITmfEventAspect;
import org.eclipse.tracecompass.tmf.core.trace.TraceValidationStatus;
import org.eclipse.tracecompass.tmf.ctf.core.trace.CtfTraceValidationStatus;

/**
 * Traces generated by the exatracer and ROCm runtime
 *
 * @author Arnaud Fiorini
 */
public class RocmExatracerTrace extends RocmTrace implements IGpuTrace {

    private static final int CONFIDENCE = 101;

    /**
     * Constructor
     */
    public RocmExatracerTrace() {
        super();
    }

    private static LinuxPidAspect fVpidAspect = new LinuxPidAspect() {
        @Override
        public @Nullable Integer resolve(ITmfEvent event) {
            Long fieldValue = event.getContent().getFieldValue(Long.class, "context._vpid"); //$NON-NLS-1$
            if (fieldValue != null) {
                return fieldValue.intValue();
            }
            return null;
        }
    };
    private static LinuxTidAspect fVtidAspect = new LinuxTidAspect() {
        @Override
        public @Nullable Integer resolve(ITmfEvent event) {
            Long fieldValue = event.getContent().getFieldValue(Long.class, "context._vtid"); //$NON-NLS-1$
            if (fieldValue != null) {
                return fieldValue.intValue();
            }
            fieldValue = event.getContent().getFieldValue(Long.class, RocmExatracerTraceEventLayout.getInstance().fieldThreadId());
            if (fieldValue != null) {
                return fieldValue.intValue();
            }
            return null;
        }
    };

    @Override
    public @Nullable IStatus validate(@Nullable IProject project, @Nullable String path) {
        IStatus status = super.validate(project, path);
        if (status instanceof CtfTraceValidationStatus) {
            Collection<String> eventNames = ((CtfTraceValidationStatus) status).getEventNames();
            /**
             * Make sure the trace contains an event from either HSA or HIP provider
             */
            if (eventNames.stream().noneMatch(event -> event.startsWith(RocmExatracerTraceEventLayout.HSA)) &&
                    eventNames.stream().noneMatch(event -> event.startsWith(RocmExatracerTraceEventLayout.HIP))) {
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "This trace was not recognized as a ROCm trace."); //$NON-NLS-1$
            }
            Map<String, String> environment = ((CtfTraceValidationStatus) status).getEnvironment();
            String domain = environment.get("tracer_name"); //$NON-NLS-1$
            if (domain == null || !domain.equals("\"lttng-ust\"")) { //$NON-NLS-1$
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "This trace was not recognized as a ROCm trace."); //$NON-NLS-1$
            }
            return new TraceValidationStatus(CONFIDENCE, Activator.PLUGIN_ID);
        }
        return status;
    }

    @Override
    public Iterable<ITmfEventAspect<?>> getEventAspects() {
        Iterable<ITmfEventAspect<?>> oldAspects = super.getEventAspects();
        List<ITmfEventAspect<?>> aspects = new ArrayList<>();
        for (ITmfEventAspect<?> aspect : oldAspects) {
            aspects.add(aspect);
        }
        aspects.add(fVpidAspect);
        aspects.add(fVtidAspect);
        return aspects;
    }

    @Override
    public @NonNull IGpuTraceEventLayout getGpuTraceEventLayout() {
        return RocmExatracerTraceEventLayout.getInstance();
    }
}
