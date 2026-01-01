package infra.output;

import java.nio.file.Path;

import java.util.List;

import domain.model.AliasSqlResult;

import domain.model.ConversionWarning;

import domain.output.ResultWriter;

import domain.model.TobeSelectOutputRow;

import domain.model.TobeDmlParamRow;

/** AliasSqlResultXlsxWriter 기반 XLSX 결과 출력 구현체(호환 유지). */
public final class XlsxResultWriter implements ResultWriter {

    private final AliasSqlResultXlsxWriter delegate;

    public XlsxResultWriter(AliasSqlResultXlsxWriter delegate) {
        this.delegate = delegate;
    }

    @Override
    public void write(
            Path resultXlsx,
            List<AliasSqlResult> results,
            List<ConversionWarning> warnings,
            List<TobeSelectOutputRow> tobeSelectOutputs,
            List<TobeDmlParamRow> tobeDmlParams
    ) {
        delegate.write(resultXlsx, results, warnings, tobeSelectOutputs, tobeDmlParams);
    }
}