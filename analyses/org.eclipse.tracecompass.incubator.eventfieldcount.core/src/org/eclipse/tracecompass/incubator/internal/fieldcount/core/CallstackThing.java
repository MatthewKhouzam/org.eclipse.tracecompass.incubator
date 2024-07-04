package org.eclipse.tracecompass.incubator.internal.fieldcount.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.analysis.profiling.core.callstack.CallStackAnalysis;
import org.eclipse.tracecompass.analysis.profiling.core.instrumented.InstrumentedCallStackAnalysis;
import org.eclipse.tracecompass.analysis.timing.core.statistics.IStatistics;
import org.eclipse.tracecompass.analysis.timing.core.statistics.Statistics;
import org.eclipse.tracecompass.incubator.internal.fieldcount.core.LamiHelpers.LamiCategoryAspect;
import org.eclipse.tracecompass.incubator.internal.fieldcount.core.LamiHelpers.LamiString;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.module.LamiAnalysis;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.module.LamiResultTable;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.module.LamiTableClass;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.module.LamiTableEntry;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.types.LamiLongNumber;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.types.LamiTimeRange;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.types.LamiTimestamp;
import org.eclipse.tracecompass.segmentstore.core.ISegment;
import org.eclipse.tracecompass.segmentstore.core.ISegmentStore;
import org.eclipse.tracecompass.segmentstore.core.segment.interfaces.INamedSegment;
import org.eclipse.tracecompass.tmf.core.resources.ITmfMarker;
import org.eclipse.tracecompass.tmf.core.timestamp.TmfTimeRange;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceUtils;

public class CallstackThing extends LamiAnalysis {

    public CallstackThing() {
        super("Find Anomalies", false, trace -> canRun(trace), Collections.emptyList()); //$NON-NLS-1$
    }

    /**
     * @param trace
     * @return
     */
    private static boolean canRun(@NonNull ITmfTrace trace) {
        return TmfTraceUtils.getAnalysisModulesOfClass(trace, CallStackAnalysis.class).iterator().hasNext() || TmfTraceUtils.getAnalysisModulesOfClass(trace, InstrumentedCallStackAnalysis.class).iterator().hasNext();
    }

    @Override
    public boolean canExecute(ITmfTrace trace) {
        return canRun(trace);
    }

    @Override
    public List<LamiResultTable> execute(ITmfTrace trace, @Nullable TmfTimeRange timeRange, String extraParamsString, IProgressMonitor monitor) throws CoreException {
        Iterable<CallStackAnalysis> csas = TmfTraceUtils.getAnalysisModulesOfClass(trace, CallStackAnalysis.class);
        Iterable<InstrumentedCallStackAnalysis> isas = TmfTraceUtils.getAnalysisModulesOfClass(trace, InstrumentedCallStackAnalysis.class);
        ISegmentStore<ISegment> callStackSeries = null;
        if (csas.iterator().hasNext()) {
            for (CallStackAnalysis anal : csas) {
                anal.schedule();
                anal.waitForCompletion();
                callStackSeries = anal.getCallStackSeries();
                if (callStackSeries != null) {
                    break;
                }
            }
        } else {
            for (InstrumentedCallStackAnalysis anal : isas) {
                anal.schedule();
                anal.waitForCompletion();
                callStackSeries = anal.getCallStackSeries();
                if (callStackSeries != null) {
                    break;
                }
            }

        }
        Map<String, IStatistics<Long>> stats = new HashMap<>();

        if (callStackSeries == null) {
            return Collections.emptyList();
        }
        IFile bookmarksFile = getBookmarksFile(trace);
        Iterable<ISegment> intersectingElements = timeRange == null ? callStackSeries : callStackSeries.getIntersectingElements(timeRange.getStartTime().toNanos(), timeRange.getEndTime().toNanos());
        for (ISegment elem : intersectingElements) {
            if (elem instanceof INamedSegment) {
                INamedSegment iNamedSegment = (INamedSegment) elem;
                String name = iNamedSegment.getName();
                stats.computeIfAbsent(name, unused -> new Statistics<Long>());
                IStatistics<Long> statsInstance = stats.get(name);
                if (statsInstance != null) {
                    statsInstance.update(elem.getLength());
                }
            }
        }
        int n = 0;
        List<LamiResultTable> results = new ArrayList<>();
        List<LamiTableEntry> entries = new ArrayList<>();
        List<INamedSegment> markers = new ArrayList<>();
        for (ISegment elem : intersectingElements) {
            if (elem instanceof INamedSegment) {
                INamedSegment iNamedSegment = (INamedSegment) elem;
                String name = iNamedSegment.getName();
                IStatistics<Long> ble = stats.get(name);
                if (ble != null) {
                    double outlier = ble.getMean() + ble.getStdDev() * 3;
                    if (elem.getLength() > outlier) {
                        int index = n++;
                        entries.add(new LamiTableEntry(List.of(new LamiLongNumber((long) index),
                                new LamiTimeRange(new LamiTimestamp(elem.getStart()),
                                        new LamiTimestamp(elem.getEnd())),
                                new LamiString(name))));
                        markers.add(iNamedSegment);
                    }

                }
            }
        }
        Job bookmarker = new Job("Creating bookmarks") { //$NON-NLS-1$
            @Override
            protected IStatus run(@Nullable IProgressMonitor innerMonitor) {

                int index = 0;
                for (INamedSegment elem : markers) {
                    if (innerMonitor != null && innerMonitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    try {
                        final IMarker bookmark = bookmarksFile.createMarker(IMarker.BOOKMARK);
                        bookmark.setAttribute(IMarker.MESSAGE, Objects.requireNonNull(elem.getName()));
                        bookmark.setAttribute(ITmfMarker.MARKER_RANK, index++);
                        bookmark.setAttribute(ITmfMarker.MARKER_TIME, Long.toString(elem.getStart()));
                        bookmark.setAttribute(ITmfMarker.MARKER_DURATION, Long.toString(elem.getLength()));
                        bookmark.setAttribute(ITmfMarker.MARKER_COLOR, "RGBA {127, 0, 0, 16}"); //$NON-NLS-1$
                    } catch (CoreException e) {
                        return Status.error(e.getMessage());
                    }

                }
                return Status.OK_STATUS;
            }
        };
        bookmarker.schedule();
        LamiTableClass ltc = new LamiTableClass("anomaly", "Anomaly list", List.of(new LamiCategoryAspect("Anomaly", 0), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                new LamiCategoryAspect("Time Range", 1), new LamiCategoryAspect("Symbol", 2)), Collections.emptyList()); //$NON-NLS-1$ //$NON-NLS-2$
        LamiResultTable lrt = new LamiResultTable(new LamiTimeRange(new LamiTimestamp(trace.getStartTime().toNanos()), new LamiTimestamp(trace.getEndTime().toNanos())), ltc, entries);
        results.add(lrt);
        return results;

    }

    public IFile getBookmarksFile(ITmfTrace trace) {
        IFile file = null;
        IResource resource = trace.getResource();
        if (resource instanceof IFile) {
            file = (IFile) resource;
        } else if (resource instanceof IFolder) {
            final IFolder folder = (IFolder) resource;
            file = folder.getFile(folder.getName() + '_');
            if (!file.exists()) {
                try {
                    file.touch(new NullProgressMonitor());
                } catch (CoreException e) {
                    Activator.getInstance().logError(e.getMessage(), e);
                }
            }
        }
        return file;
    }

}
