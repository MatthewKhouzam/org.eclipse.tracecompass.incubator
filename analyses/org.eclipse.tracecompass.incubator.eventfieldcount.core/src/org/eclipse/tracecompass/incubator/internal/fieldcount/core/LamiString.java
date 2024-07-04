package org.eclipse.tracecompass.incubator.internal.fieldcount.core;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.aspect.LamiGenericAspect;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.types.LamiData;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.types.LamiTimeRange;
import org.eclipse.tracecompass.internal.provisional.analysis.lami.core.types.LamiTimestamp;
import org.eclipse.tracecompass.tmf.core.timestamp.TmfTimeRange;

class LamiHelpers {
    /**
     * Todo, move to LAMI
     */
    public static LamiTimeRange createTimeRange(TmfTimeRange timeRange) {
        return new LamiTimeRange(new LamiTimestamp(timeRange.getStartTime().toNanos()), new LamiTimestamp(timeRange.getStartTime().toNanos()));
    }

    /**
     * Todo, move to LAMI
     */
    public static final class LamiString extends LamiData {
        private final String fElement;

        LamiString(String element) {
            fElement = element;
        }

        @Override
        public @NonNull String toString() {
            return fElement;
        }
    }

    /**
     * Count aspect, generic
     *
     * TODO: move to LAMI
     *
     * @author Matthew Khouzam
     *
     */
    public static final class LamiCountAspect extends LamiGenericAspect {

        LamiCountAspect(String name, int column) {
            super(name, null, column, true, false);
        }
    }

    /**
     * Category aspect, generic
     *
     * TODO: move to LAMI
     *
     * @author Matthew Khouzam
     *
     */
    public static final class LamiCategoryAspect extends LamiGenericAspect {

        LamiCategoryAspect(String name, int column) {
            super(name, null, column, false, false);
        }
    }
}