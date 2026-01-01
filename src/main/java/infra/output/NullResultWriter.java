package infra.output;

import java.nio.file.Path;

import java.util.List;

import domain.model.AliasSqlResult;

import domain.model.ConversionWarning;

import domain.output.ResultWriter;

import domain.model.TobeSelectOutputRow;

import domain.model.TobeDmlParamRow;

/** No-op implementation (feature toggle). */
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