package infra.output;

import domain.model.AliasSqlResult;
import domain.model.ConversionWarning;
import domain.model.TobeDmlParamRow;
import domain.model.TobeSelectOutputRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * XLSX report writer.
 *
 * <p>Sheets:
 * <ul>
 *   <li>result: SUCCESS/SKIP summary</li>
 *   <li>warnings: non-fatal warnings (standard codes)</li>
 *   <li>tobe_select_outputs: derived SELECT output ids (TOBE mode only)</li>
 *   <li>tobe_dml_params: derived DML parameters (TOBE mode only)</li>
 * </ul>
 */
public final class AliasSqlResultXlsxWriter {

    private static void writeResultSheet(Workbook wb, List<AliasSqlResult> results) {
        Sheet sh = wb.createSheet("result");
        int r = 0;
        Row header = sh.createRow(r++);
        header.createCell(0)
                .setCellValue("status");
        header.createCell(1)
                .setCellValue("serviceClass");
        header.createCell(2)
                .setCellValue("namespace");
        header.createCell(3)
                .setCellValue("sqlId");
        header.createCell(4)
                .setCellValue("message");
        header.createCell(5)
                .setCellValue("sqlTextFrom");
        header.createCell(6)
                .setCellValue("fallbackUsed");
        header.createCell(7)
                .setCellValue("fallbackSource");
        header.createCell(8)
                .setCellValue("detail");

        for (AliasSqlResult it : results) {
            Row row = sh.createRow(r++);
            row.createCell(0)
                    .setCellValue(nullToEmpty(it.getStatus()));
            row.createCell(1)
                    .setCellValue(nullToEmpty(it.getServiceClass()));
            row.createCell(2)
                    .setCellValue(nullToEmpty(it.getNamespace()));
            row.createCell(3)
                    .setCellValue(nullToEmpty(it.getSqlId()));
            row.createCell(4)
                    .setCellValue(nullToEmpty(it.getMessage()));
            row.createCell(5)
                    .setCellValue(nullToEmpty(it.getSqlTextFrom()));
            row.createCell(6)
                    .setCellValue(it.isFallbackUsed());
            row.createCell(7)
                    .setCellValue(nullToEmpty(it.getFallbackSource()));
            row.createCell(8)
                    .setCellValue(nullToEmpty(it.getDetail()));
        }
    }

    private static void writeWarningsSheet(Workbook wb, List<ConversionWarning> warnings) {
        Sheet sh = wb.createSheet("warnings");
        int r = 0;
        Row header = sh.createRow(r++);
        header.createCell(0)
                .setCellValue("code");
        header.createCell(1)
                .setCellValue("serviceClass");
        header.createCell(2)
                .setCellValue("namespace");
        header.createCell(3)
                .setCellValue("sqlId");
        header.createCell(4)
                .setCellValue("message");
        header.createCell(5)
                .setCellValue("detail");

        for (ConversionWarning w : warnings) {
            Row row = sh.createRow(r++);
            row.createCell(0)
                    .setCellValue(w.getCode() == null ? "" : w.getCode()
                            .name());
            row.createCell(1)
                    .setCellValue(nullToEmpty(w.getServiceClass()));
            row.createCell(2)
                    .setCellValue(nullToEmpty(w.getNamespace()));
            row.createCell(3)
                    .setCellValue(nullToEmpty(w.getSqlId()));
            row.createCell(4)
                    .setCellValue(nullToEmpty(w.getMessage()));
            row.createCell(5)
                    .setCellValue(nullToEmpty(w.getDetail()));
        }
    }

    private static void writeSelectOutputsSheet(Workbook wb, List<TobeSelectOutputRow> rows) {
        Sheet sh = wb.createSheet("tobe_select_outputs");
        int r = 0;
        Row header = sh.createRow(r++);
        header.createCell(0)
                .setCellValue("serviceClass");
        header.createCell(1)
                .setCellValue("namespace");
        header.createCell(2)
                .setCellValue("sqlId");
        header.createCell(3)
                .setCellValue("seq");
        header.createCell(4)
                .setCellValue("outputName");
        header.createCell(5)
                .setCellValue("outputLowerCamel");
        header.createCell(6)
                .setCellValue("expression");
        header.createCell(7)
                .setCellValue("trailingComment");

        for (TobeSelectOutputRow it : rows) {
            Row row = sh.createRow(r++);
            row.createCell(0)
                    .setCellValue(nullToEmpty(it.serviceClass));
            row.createCell(1)
                    .setCellValue(nullToEmpty(it.namespace));
            row.createCell(2)
                    .setCellValue(nullToEmpty(it.sqlId));
            row.createCell(3)
                    .setCellValue(it.seq);
            row.createCell(4)
                    .setCellValue(nullToEmpty(it.outputName));
            row.createCell(5)
                    .setCellValue(nullToEmpty(it.outputLowerCamel));
            row.createCell(6)
                    .setCellValue(nullToEmpty(it.expression));
            row.createCell(7)
                    .setCellValue(nullToEmpty(it.trailingComment));
        }
    }

    private static void writeDmlParamsSheet(Workbook wb, List<TobeDmlParamRow> rows) {
        Sheet sh = wb.createSheet("tobe_dml_params");
        int r = 0;
        Row header = sh.createRow(r++);
        header.createCell(0)
                .setCellValue("serviceClass");
        header.createCell(1)
                .setCellValue("namespace");
        header.createCell(2)
                .setCellValue("sqlId");
        header.createCell(3)
                .setCellValue("seq");
        header.createCell(4)
                .setCellValue("dmlType");
        header.createCell(5)
                .setCellValue("paramName");
        header.createCell(6)
                .setCellValue("paramLowerCamel");

        for (TobeDmlParamRow it : rows) {
            Row row = sh.createRow(r++);
            row.createCell(0)
                    .setCellValue(nullToEmpty(it.serviceClass));
            row.createCell(1)
                    .setCellValue(nullToEmpty(it.namespace));
            row.createCell(2)
                    .setCellValue(nullToEmpty(it.sqlId));
            row.createCell(3)
                    .setCellValue(it.seq);
            row.createCell(4)
                    .setCellValue(nullToEmpty(it.dmlType));
            row.createCell(5)
                    .setCellValue(nullToEmpty(it.paramName));
            row.createCell(6)
                    .setCellValue(nullToEmpty(it.paramLowerCamel));
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Backward-compatible overload (warnings empty).
     */
    public void write(
            Path resultXlsx,
            List<AliasSqlResult> results,
            List<TobeSelectOutputRow> tobeSelectOutputs,
            List<TobeDmlParamRow> tobeDmlParams
    ) {
        write(resultXlsx, results, List.of(), tobeSelectOutputs, tobeDmlParams);
    }

    public void write(
            Path resultXlsx,
            List<AliasSqlResult> results,
            List<ConversionWarning> warnings,
            List<TobeSelectOutputRow> tobeSelectOutputs,
            List<TobeDmlParamRow> tobeDmlParams
    ) {
        if (resultXlsx == null) throw new IllegalArgumentException("resultXlsx is null");
        if (results == null) throw new IllegalArgumentException("results is null");
        if (warnings == null) throw new IllegalArgumentException("warnings is null");
        if (tobeSelectOutputs == null) throw new IllegalArgumentException("tobeSelectOutputs is null");
        if (tobeDmlParams == null) throw new IllegalArgumentException("tobeDmlParams is null");

        try {
            Path parent = resultXlsx.toAbsolutePath()
                    .normalize()
                    .getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create resultXlsx parent dir: " + resultXlsx, e);
        }

        try (Workbook wb = new XSSFWorkbook()) {
            writeResultSheet(wb, results);
            writeWarningsSheet(wb, warnings);
            writeSelectOutputsSheet(wb, tobeSelectOutputs);
            writeDmlParamsSheet(wb, tobeDmlParams);

            try (OutputStream os = Files.newOutputStream(resultXlsx)) {
                wb.write(os);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write xlsx: " + resultXlsx, e);
        }
    }
}
