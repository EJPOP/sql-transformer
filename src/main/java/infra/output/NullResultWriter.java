package infra.output;

import domain.model.AliasSqlResult;
import domain.model.ConversionWarning;
import domain.model.TobeDmlParamRow;
import domain.model.TobeSelectOutputRow;
import domain.output.ResultWriter;

import java.nio.file.Path;
import java.util.List;

/**
 * No-op implementation (feature toggle).
 */
public final class NullResultWriter implements ResultWriter {
    @Override
    public void write(
            Path resultXlsx,
            List<AliasSqlResult> results,
            List<ConversionWarning> warnings,
            List<TobeSelectOutputRow> tobeSelectOutputs,
            List<TobeDmlParamRow> tobeDmlParams
    ) {
        // intentionally no-op
    }
}