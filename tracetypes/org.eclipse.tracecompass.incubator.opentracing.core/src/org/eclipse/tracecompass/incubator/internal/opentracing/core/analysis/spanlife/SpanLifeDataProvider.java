/*******************************************************************************
 * Copyright (c) 2018 Ericsson
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.tracecompass.incubator.internal.opentracing.core.analysis.spanlife;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.incubator.internal.opentracing.core.analysis.spanlife.SpanLifeEntryModel.LogEvent;
import org.eclipse.tracecompass.incubator.internal.opentracing.core.event.IOpenTracingConstants;
import org.eclipse.tracecompass.internal.tmf.core.model.filters.TimeGraphStateQueryFilter;
import org.eclipse.tracecompass.internal.tmf.core.model.timegraph.AbstractTimeGraphDataProvider;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystem;
import org.eclipse.tracecompass.statesystem.core.exceptions.AttributeNotFoundException;
import org.eclipse.tracecompass.statesystem.core.exceptions.StateSystemDisposedException;
import org.eclipse.tracecompass.statesystem.core.exceptions.TimeRangeException;
import org.eclipse.tracecompass.statesystem.core.interval.ITmfStateInterval;
import org.eclipse.tracecompass.tmf.core.model.CommonStatusMessage;
import org.eclipse.tracecompass.tmf.core.model.filters.SelectionTimeQueryFilter;
import org.eclipse.tracecompass.tmf.core.model.filters.TimeQueryFilter;
import org.eclipse.tracecompass.tmf.core.model.timegraph.ITimeGraphArrow;
import org.eclipse.tracecompass.tmf.core.model.timegraph.ITimeGraphRowModel;
import org.eclipse.tracecompass.tmf.core.model.timegraph.ITimeGraphState;
import org.eclipse.tracecompass.tmf.core.model.timegraph.TimeGraphEntryModel;
import org.eclipse.tracecompass.tmf.core.model.timegraph.TimeGraphRowModel;
import org.eclipse.tracecompass.tmf.core.model.timegraph.TimeGraphState;
import org.eclipse.tracecompass.tmf.core.response.ITmfResponse;
import org.eclipse.tracecompass.tmf.core.response.TmfModelResponse;
import org.eclipse.tracecompass.tmf.core.timestamp.TmfTimestamp;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;

import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.TreeMultimap;

/**
 * Data provider that will show the object lifespans.
 *
 * @author Katherine Nadeau
 *
 */
@SuppressWarnings("restriction")
public class SpanLifeDataProvider extends AbstractTimeGraphDataProvider<@NonNull SpanLifeAnalysis, @NonNull TimeGraphEntryModel> {

    private static final int MARKER_SIZE = 500;

    /**
     * Suffix for dataprovider ID
     */
    public static final String SUFFIX = ".dataprovider"; //$NON-NLS-1$

    /**
     * Constructor
     *
     * @param trace
     *            the trace this provider represents
     * @param analysisModule
     *            the analysis encapsulated by this provider
     */
    public SpanLifeDataProvider(ITmfTrace trace, SpanLifeAnalysis analysisModule) {
        super(trace, analysisModule);
    }

    @Override
    public @NonNull TmfModelResponse<@NonNull List<@NonNull ITimeGraphArrow>> fetchArrows(@NonNull TimeQueryFilter filter, @Nullable IProgressMonitor monitor) {
        return new TmfModelResponse<>(null, ITmfResponse.Status.COMPLETED, CommonStatusMessage.COMPLETED);
    }

    @Override
    public @NonNull TmfModelResponse<@NonNull Map<@NonNull String, @NonNull String>> fetchTooltip(@NonNull SelectionTimeQueryFilter filter, @Nullable IProgressMonitor monitor) {
        ITmfStateSystem ss = getAnalysisModule().getStateSystem();
        Map<@NonNull Long, @NonNull Integer> entries = getSelectedEntries(filter);
        Collection<@NonNull Integer> quarks = entries.values();
        long startTime = filter.getStart();
        long hoverTime = filter.getTimesRequested()[1];
        long endTime = filter.getEnd();
        if (ss == null || quarks.size() != 1 || !getAnalysisModule().isQueryable(hoverTime)) {
            return new TmfModelResponse<>(null, ITmfResponse.Status.COMPLETED, CommonStatusMessage.COMPLETED);
        }

        int traceLogsQuark = ITmfStateSystem.INVALID_ATTRIBUTE;
        try {
            String traceId = ss.getFullAttributePathArray(quarks.iterator().next())[0];
            traceLogsQuark = ss.getQuarkRelative(ss.getQuarkAbsolute(traceId), IOpenTracingConstants.LOGS);
        } catch (AttributeNotFoundException e) {
            return new TmfModelResponse<>(null, ITmfResponse.Status.CANCELLED, CommonStatusMessage.TASK_CANCELLED);
        }

        int spanLogQuark = getLogQuark(ss, ss.getAttributeName(quarks.iterator().next()), ss.getSubAttributes(traceLogsQuark, false));

        try {
            Map<@NonNull String, @NonNull String> retMap = new HashMap<>();
            if (spanLogQuark != ITmfStateSystem.INVALID_ATTRIBUTE) {
                Long ssStartTime = startTime == Long.MIN_VALUE ? ss.getStartTime() : startTime;
                Long ssEndTime = endTime == Long.MIN_VALUE ? ss.getCurrentEndTime() : endTime;
                Long deviationAccepted = (ssEndTime - ssStartTime) / MARKER_SIZE;
                for (ITmfStateInterval state : ss.query2D(Collections.singletonList(spanLogQuark), Math.max(hoverTime - deviationAccepted, ssStartTime), Math.min(hoverTime + deviationAccepted, ssEndTime))) {
                    Object object = state.getValue();
                    if (object instanceof String) {
                        String logs = (String) object;
                        String timestamp = TmfTimestamp.fromNanos(state.getStartTime()).toString();
                        if (timestamp != null) {
                            retMap.put("log timestamp", timestamp); //$NON-NLS-1$
                        }
                        String[] fields = logs.split("~"); //$NON-NLS-1$
                        for (String field : fields) {
                            retMap.put(field.substring(0, field.indexOf(':')), field.substring(field.indexOf(':') + 1));
                        }
                        return new TmfModelResponse<>(retMap, ITmfResponse.Status.COMPLETED, CommonStatusMessage.COMPLETED);
                    }
                }
            }
            return new TmfModelResponse<>(retMap, ITmfResponse.Status.COMPLETED, CommonStatusMessage.COMPLETED);
        } catch (StateSystemDisposedException e) {
            return new TmfModelResponse<>(null, ITmfResponse.Status.CANCELLED, CommonStatusMessage.TASK_CANCELLED);
        }
    }

    @Override
    public @NonNull String getId() {
        return getAnalysisModule().getId() + SUFFIX;
    }

    @Override
    protected @Nullable List<@NonNull ITimeGraphRowModel> getRowModel(@NonNull ITmfStateSystem ss, @NonNull SelectionTimeQueryFilter filter, @Nullable IProgressMonitor monitor) throws StateSystemDisposedException {
        TreeMultimap<Integer, ITmfStateInterval> intervals = TreeMultimap.create(Comparator.naturalOrder(),
                Comparator.comparing(ITmfStateInterval::getStartTime));
        Map<@NonNull Long, @NonNull Integer> entries = getSelectedEntries(filter);
        Collection<Long> times = getTimes(filter, ss.getStartTime(), ss.getCurrentEndTime());
        /* Do the actual query */
        for (ITmfStateInterval interval : ss.query2D(entries.values(), times)) {
            if (monitor != null && monitor.isCanceled()) {
                return Collections.emptyList();
            }
            intervals.put(interval.getAttribute(), interval);
        }
        Map<@NonNull Integer, @NonNull Predicate<@NonNull Map<@NonNull String, @NonNull String>>> predicates = new HashMap<>();
        if (filter instanceof TimeGraphStateQueryFilter) {
            TimeGraphStateQueryFilter timeEventFilter = (TimeGraphStateQueryFilter) filter;
            predicates.putAll(computeRegexPredicate(timeEventFilter));
        }
        List<@NonNull ITimeGraphRowModel> rows = new ArrayList<>();
        for (Map.Entry<@NonNull Long, @NonNull Integer> entry : entries.entrySet()) {
            if (monitor != null && monitor.isCanceled()) {
                return Collections.emptyList();
            }

            List<ITimeGraphState> eventList = new ArrayList<>();
            for (ITmfStateInterval interval : intervals.get(entry.getValue())) {
                long startTime = interval.getStartTime();
                long duration = interval.getEndTime() - startTime + 1;
                Object state = interval.getValue();
                TimeGraphState value = new TimeGraphState(startTime, duration, state == null ? Integer.MIN_VALUE : 0);
                addToStateList(eventList, value, entry.getKey(), predicates, monitor);
            }
            rows.add(new TimeGraphRowModel(entry.getKey(), eventList));

        }
        return rows;
    }

    @Override
    protected boolean isCacheable() {
        return true;
    }

    @Override
    protected @NonNull List<@NonNull TimeGraphEntryModel> getTree(@NonNull ITmfStateSystem ss, @NonNull TimeQueryFilter filter, @Nullable IProgressMonitor monitor) throws StateSystemDisposedException {
        Builder<@NonNull TimeGraphEntryModel> builder = new Builder<>();
        long rootId = getId(ITmfStateSystem.ROOT_ATTRIBUTE);
        builder.add(new TimeGraphEntryModel(rootId, -1, String.valueOf(getTrace().getName()), ss.getStartTime(), ss.getCurrentEndTime()));

        for (int traceQuark : ss.getSubAttributes(ITmfStateSystem.ROOT_ATTRIBUTE, false)) {
            addTrace(ss, builder, traceQuark, rootId);
        }

        return builder.build();
    }

    private void addTrace(ITmfStateSystem ss, Builder<@NonNull TimeGraphEntryModel> builder, int quark, long parentId) {
        List<@NonNull Integer> logsQuarks;
        try {
            int logsQuark = ss.getQuarkRelative(quark, IOpenTracingConstants.LOGS);
            logsQuarks = ss.getSubAttributes(logsQuark, false);
        } catch (AttributeNotFoundException e) {
            logsQuarks = new ArrayList<>();
        }

        int openTracingSpansQuark;
        try {
            openTracingSpansQuark = ss.getQuarkRelative(quark, SpanLifeStateProvider.OPEN_TRACING_ATTRIBUTE);
        } catch (AttributeNotFoundException e) {
            return;
        }

        long traceQuarkId = getId(quark);
        builder.add(new TimeGraphEntryModel(traceQuarkId, parentId, ss.getAttributeName(quark), ss.getStartTime(), ss.getCurrentEndTime()));

        int ustSpansQuark;
        try {
            ustSpansQuark = ss.getQuarkRelative(quark, SpanLifeStateProvider.UST_ATTRIBUTE);
        } catch (AttributeNotFoundException e) {
            addChildren(ss, builder, openTracingSpansQuark, traceQuarkId, logsQuarks);
            return;
        }
        addUstChildren(ss, builder, openTracingSpansQuark, ustSpansQuark, traceQuarkId, logsQuarks);
    }

    private void addChildren(ITmfStateSystem ss, Builder<@NonNull TimeGraphEntryModel> builder, int quark, long parentId, List<Integer> logsQuarks) {
        for (Integer child : ss.getSubAttributes(quark, false)) {
            long childId = getId(child);
            String childName = ss.getAttributeName(child);
            if (!childName.equals(IOpenTracingConstants.LOGS)) {
                List<LogEvent> logs = new ArrayList<>();
                int logQuark = getLogQuark(ss, childName, logsQuarks);
                try {
                    for (ITmfStateInterval interval : ss.query2D(Collections.singletonList(logQuark), ss.getStartTime(), ss.getCurrentEndTime())) {
                        if (!interval.getStateValue().isNull()) {
                            logs.add(new LogEvent(interval.getStartTime(), String.valueOf(interval.getValue())));
                        }
                    }
                } catch (IndexOutOfBoundsException | TimeRangeException | StateSystemDisposedException e) {
                }
                builder.add(new SpanLifeEntryModel(childId, parentId, getSpanName(childName), ss.getStartTime(), ss.getCurrentEndTime(), logs));
                addChildren(ss, builder, child, childId, logsQuarks);
            }
        }
    }

    private void addUstChildren(ITmfStateSystem ss, Builder<@NonNull TimeGraphEntryModel> builder, int openTracingQuark, int ustQuark, long parentId, List<Integer> logsQuarks) {
        for (Integer child : ss.getSubAttributes(openTracingQuark, false)) {
            String childName = ss.getAttributeName(child);

            List<LogEvent> logs = new ArrayList<>();
            int logQuark = getLogQuark(ss, childName, logsQuarks);
            try {
                for (ITmfStateInterval interval : ss.query2D(Collections.singletonList(logQuark), ss.getStartTime(), ss.getCurrentEndTime())) {
                    if (!interval.getStateValue().isNull()) {
                        logs.add(new LogEvent(interval.getStartTime(), String.valueOf(interval.getValue())));
                    }
                }
            } catch (IndexOutOfBoundsException | TimeRangeException | StateSystemDisposedException e) {
            }

            String spanId = childName.substring(childName.lastIndexOf('/') + 1, childName.length());

            int ustSpan;
            try {
                ustSpan = ss.getQuarkRelative(ustQuark, spanId);
            } catch (AttributeNotFoundException e) {
                return;
            }
            long childId = getId(ustSpan);
            builder.add(new SpanLifeEntryModel(childId, parentId, getSpanName(ss.getAttributeName(child)), ss.getStartTime(), ss.getCurrentEndTime(), logs));
            addUstChildren(ss, builder, child, ustQuark, childId, logsQuarks);
        }
    }

    private static int getLogQuark(ITmfStateSystem ss, String spanName, List<Integer> logsQuarks) {
        for (int logsQuark : logsQuarks) {
            if (ss.getAttributeName(logsQuark).equals(spanName.substring(spanName.lastIndexOf('/') + 1, spanName.length()))) {
                return logsQuark;
            }
        }
        return ITmfStateSystem.INVALID_ATTRIBUTE;
    }

    private static String getSpanName(String attributeName) {
        return attributeName.substring(0, attributeName.lastIndexOf('/'));
    }
}