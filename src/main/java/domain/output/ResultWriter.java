package domain.output;

import java.nio.file.Path;

import java.util.List;

import domain.model.AliasSqlResult;

import domain.model.ConversionWarning;

import domain.model.TobeSelectOutputRow;

import domain.model.TobeDmlParamRow;

/** 결과 리포트를 저장하는 책임. */
public interface ResultWriter {

    void write(
            Path resultXlsx,
            List<AliasSqlResult> results,
            List<ConversionWarning> warnings,
            List<TobeSelectOutputRow> tobeSelectOutputs,
            List<TobeDmlParamRow> tobeDmlParams
    );
}