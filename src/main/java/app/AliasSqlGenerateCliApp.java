package app;

import domain.callchain.ServiceSqlCall;

import mybatis.SqlStatementRegistry;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.ArrayList;

import java.util.Collections;

import java.util.List;

import java.util.Map;

import cli.AliasSqlGenerateCli;

import cli.CliArgParser;

import cli.CliPathResolver;

import cli.CliProgressMonitor;

import cli.CliReflectionUtil;

import domain.convert.AliasSqlGenerator;

import domain.convert.AsisResidueValidator;

import domain.convert.TobeSqlAnalyzer;

import domain.mapping.ColumnMappingRegistry;

import domain.model.AliasSqlResult;

import domain.model.ConversionContext;

import domain.model.ConversionWarning;

import domain.model.ConversionWarningSink;

import domain.model.ListConversionWarningSink;

import domain.model.WarningCode;

import domain.output.ResultWriter;

import domain.output.SqlOutputWriter;

import domain.text.SqlTextProvider;

import domain.text.SqlTextResolution;

import domain.model.TobeSelectOutputRow;

import domain.model.TobeDmlParamRow;

/** CLI entry (invoked by {@link AliasSqlGenerateCli}). */
public final class AliasSqlGenerateCliApp {

    private static final String PROP_PRETTY = "pretty";

    private AliasSqlGenerateCliApp() {}

    public static void main(String[] args) {

        long t0 = System.nanoTime();
        Map<String, String> argv = CliArgParser.parseArgs(args);

        // ------------------------------------------------------------
        // baseDir / system / sqlsDir 결정 + system properties 반영
        // ------------------------------------------------------------
        CliPathResolver.applyBaseDirPropertyIfPresent(argv);

        Path baseDir = CliPathResolver.resolveBaseDir();
        CliPathResolver.ensureBaseDirProperty(baseDir);

        String system = CliPathResolver.resolveSystem(argv);
        CliPathResolver.ensureSystemProperty(system);

        Path sqlsDir = CliPathResolver.resolveSqlsDir(baseDir, system, argv);
        CliPathResolver.ensureSqlsDirProperty(sqlsDir);

        // ------------------------------------------------------------
        // mode / input / output
        // ------------------------------------------------------------
        AliasSqlGenerator.Mode mode = CliArgParser.parseMode(argv.get("mode"));

        // system 프리셋: 옵션이 없을 때만 기본값으로 채움
        String callchainRaw = firstNonBlank(argv.get("callchain"), argv.get("csv"));
        String mappingRaw = firstNonBlank(argv.get("mapping"), argv.get("mappingXlsx"), argv.get("mapping_column"), argv.get("mapping_column_xlsx"));

        // Default mapping directory: systems/<system>/mapping
        Path mappingDir = preferExistingDir(
                baseDir.resolve("src/main/resources/systems").resolve(system).resolve("mapping"),
                baseDir.resolve("systems").resolve(system).resolve("mapping")
        );

        // callchain: file or dir (callchain.csv/xlsx). Backward compatible with service_sql_xref.csv
        Path callchainPath = resolveFileOrDir(callchainRaw, mappingDir,
                "callchain.xlsx", "callchain.csv",
                "service_sql_xref.xlsx", "service_sql_xref.csv");

        // mapping: file or dir (mapping_column.csv/xlsx). Backward compatible with column_mapping.xlsx
        Path mappingPath = resolveFileOrDir(mappingRaw, mappingDir,
                "mapping_column.xlsx", "mapping_column.csv",
                "column_mapping.xlsx", "column_mapping.csv");


        String defaultOut = isTobeMode(mode)
                ? (system != null ? "output/" + system + "/tobe-sql" : "output/tobe-sql")
                : (system != null ? "output/" + system + "/alias-sql" : "output/alias-sql");

        String defaultResult = isTobeMode(mode)
                ? (system != null ? "output/" + system + "/tobe-sql.xlsx" : "output/tobe-sql.xlsx")
                : (system != null ? "output/" + system + "/alias-sql-result.xlsx" : "output/alias-sql-result.xlsx");

        // callchainPath / mappingPath 를 이미 만들어둔 상태라고 가정
        String callchainCsv = (callchainPath == null) ? "" : callchainPath.toString();
        String columnMappingXlsx = (mappingPath == null) ? "" : mappingPath.toString();

        Path outputSqlDir = CliPathResolver.resolvePath(baseDir, argv.getOrDefault("out", defaultOut));
        Path resultXlsx   = CliPathResolver.resolvePath(baseDir, argv.getOrDefault("result", defaultResult));

        Path callchainCsvPath = CliPathResolver.resolvePath(baseDir, callchainCsv);
        Path mappingXlsxPath  = CliPathResolver.resolvePath(baseDir, columnMappingXlsx);

        int max = CliArgParser.parseInt(argv.get("max"), -1);
        int logEvery = CliArgParser.parseInt(argv.get("logEvery"), 100);
        long slowMs = CliArgParser.parseLong(argv.get("slowMs"), 500L);
        boolean failFast = CliArgParser.parseBoolean(argv.get("failFast"), false);
        boolean logFallback = CliArgParser.parseBoolean(argv.get("logFallback"), false);

        // ------------------------------------------------------------
        // feature toggles (presence-style)
        // ------------------------------------------------------------
        boolean noFallback = CliArgParser.flag(argv, "noFallback") || CliArgParser.flag(argv, "noRegistryFallback");
        boolean noSqlOut   = CliArgParser.flag(argv, "noSqlOut")   || CliArgParser.flag(argv, "noOut");
        boolean noResult   = CliArgParser.flag(argv, "noResult")   || CliArgParser.flag(argv, "noXlsx");

        // SQL Prettier option (default ON)
        String prettyRaw = argv.containsKey("pretty")
                ? argv.get("pretty")
                : System.getProperty(PROP_PRETTY, "true");
        boolean pretty = CliArgParser.parseBoolean(prettyRaw, true);
        System.setProperty(PROP_PRETTY, String.valueOf(pretty));

        System.out.println("==================================================");
        System.out.println("[START] Alias SQL generation");
        System.out.println("[CONF] baseDir        = " + baseDir.toAbsolutePath());
        System.out.println("[CONF] system         = " + (system == null ? "" : system));
        System.out.println("[CONF] sqlsDir        = " + sqlsDir.toAbsolutePath());
        System.out.println("[CONF] mode           = " + mode);
        System.out.println("[CONF] pretty         = " + pretty + " (use --pretty=false or -D" + PROP_PRETTY + "=false)");
        System.out.println("[CONF] callchain       = " + callchainCsvPath.toAbsolutePath());
        System.out.println("[CONF] mapping        = " + mappingXlsxPath.toAbsolutePath());
        System.out.println("[CONF] out            = " + outputSqlDir.toAbsolutePath());
        System.out.println("[CONF] result         = " + resultXlsx.toAbsolutePath());
        System.out.println("[CONF] max            = " + max);
        System.out.println("[CONF] logEvery       = " + logEvery);
        System.out.println("[CONF] slowMs         = " + slowMs);
        System.out.println("[CONF] failFast       = " + failFast);
        System.out.println("[CONF] logFallback    = " + logFallback);
        System.out.println("[CONF] enableFallback = " + (!noFallback) + " (use --noFallback)");
        System.out.println("[CONF] enableSqlOut   = " + (!noSqlOut)   + " (use --noSqlOut)");
        System.out.println("[CONF] enableResult   = " + (!noResult)   + " (use --noResult)");
        System.out.println("==================================================");

        CliPathResolver.validateFileExists(callchainCsvPath, "xref callchain (--callchain)");
        CliPathResolver.validateFileExists(mappingXlsxPath, "column mapping xlsx (--mapping)");

        // warnings (collected even when result xlsx is disabled)
        List<ConversionWarning> warnings = new ArrayList<>(128);
        ConversionWarningSink warningSink = new ListConversionWarningSink(warnings);

        if (!noFallback) {
            // fallback을 켠 경우에만 sqlsDir 경고가 의미 있다.
            if (!Files.exists(sqlsDir) || !Files.isDirectory(sqlsDir)) {
                System.out.println("[WARN] sqlsDir not found or not a directory: " + sqlsDir.toAbsolutePath());
                System.out.println("       - sqls 폴더에 MyBatis mapper *.xml 파일을 복사해 넣어주세요.");
                System.out.println("       - 또는 JVM 옵션: -D" + CliPathResolver.PROP_SQLS_DIR + "=<sqls폴더경로>");
                warningSink.warn(new ConversionWarning(
                        WarningCode.SQLS_DIR_MISSING, "", "", "",
                        "sqlsDir not found: " + sqlsDir.toAbsolutePath(),
                        "enableFallback=true"
                ));
            }
        }

        if (!noSqlOut) {
            CliPathResolver.mkdirs(outputSqlDir);
        }
        if (!noResult) {
            if (resultXlsx.getParent() != null) CliPathResolver.mkdirs(resultXlsx.getParent());
        }

        // ------------------------------------------------------------
        // assemble runtime components
        // ------------------------------------------------------------
        AliasSqlGenerateComponentsFactory factory = new AliasSqlGenerateComponentsFactory();

        // SQL registry (fallback용)
        long tSqlIdx0 = System.nanoTime();
        SqlStatementRegistry sqlRegistry = factory.createSqlRegistry(!noFallback);
        if (!noFallback) {
            System.out.println("[STEP1] SqlStatementRegistry.initialize() done. elapsed=" + ms(tSqlIdx0) + "ms");
        } else {
            System.out.println("[STEP1] SqlStatementRegistry skipped (--noFallback). elapsed=" + ms(tSqlIdx0) + "ms");
        }

        // SQL text provider
        SqlTextProvider sqlTextProvider = factory.createSqlTextProvider(sqlRegistry, !noFallback);

        // column mapping
        long tMap0 = System.nanoTime();
        ColumnMappingRegistry columnMappingRegistry = factory.createColumnMappingRegistry(mappingXlsxPath);
        System.out.println("[STEP2] ColumnMappingRegistry loaded. elapsed=" + ms(tMap0) + "ms");
        System.out.println("[INIT] Column mapping size = " + columnMappingRegistry.size());

        // ✅ AS-IS 잔존 검증기
        AsisResidueValidator residueValidator = new AsisResidueValidator(columnMappingRegistry);

        // xref csv
        long tCsv0 = System.nanoTime();
        System.out.println("[STEP3] loading xref csv...");
        List<ServiceSqlCall> calls = factory.loadCalls(callchainCsvPath);
        System.out.println("[STEP3] xref csv loaded. size=" + calls.size() + ", elapsed=" + ms(tCsv0) + "ms");

        if (max > 0 && calls.size() > max) {
            calls = calls.subList(0, max);
            System.out.println("[STEP3] apply max => truncated to " + calls.size());
        }

        // heartbeat thread: 멈춘 지점 확인용
        CliProgressMonitor.startHeartbeat(calls.size());

        AliasSqlGenerator generator = factory.createGenerator(columnMappingRegistry);
        SqlOutputWriter sqlOutputWriter = factory.createSqlOutputWriter(!noSqlOut);
        ResultWriter resultWriter = factory.createResultWriter(!noResult);

        long tLoop0 = System.nanoTime();
        int total = calls.size();
        System.out.println("[STEP4] generating start. total=" + total);

        List<AliasSqlResult> results = new ArrayList<>(Math.max(16, total));

        int success = 0;
        int skip = 0;

        final boolean collectTobeMeta = isTobeMode(mode) && (!noResult);
        final List<TobeSelectOutputRow> tobeSelectOutputs =
                collectTobeMeta ? new ArrayList<>(Math.max(64, total)) : Collections.emptyList();
        final List<TobeDmlParamRow> tobeDmlParams =
                collectTobeMeta ? new ArrayList<>(Math.max(64, total)) : Collections.emptyList();

        for (int i = 0; i < total; i++) {
            ServiceSqlCall call = calls.get(i);

            String svc = safe(CliReflectionUtil.invokeString(call, "getServiceClass"));
            String ns  = safe(CliReflectionUtil.invokeString(call, "getMapperNamespace"));
            String id  = safe(CliReflectionUtil.invokeString(call, "getSqlId"));
            String key = svc + " | " + ns + "." + id;

            CliProgressMonitor.setCurrent(key, i + 1);

            long one0 = System.nanoTime();

            // 1) CSV sql_text 우선 + 2) (옵션) Registry fallback
            String csvSqlText = CliReflectionUtil.invokeString(call, "getSqlText");
            SqlTextResolution resolved = sqlTextProvider.resolve(ns, id, csvSqlText);

            String sqlText = resolved.getSqlText();
            if (resolved.isFallbackUsed()) {
                warningSink.warn(new ConversionWarning(
                        WarningCode.FALLBACK_USED, svc, ns, id,
                        "fallback used", safe(resolved.getFallbackSource())
                ));
                if (logFallback) {
                    System.out.println("[FALLBACK] " + key + " <= " + safe(resolved.getFallbackSource()));
                }
            }

            if (sqlText == null || sqlText.isBlank()) {
                skip++;
                results.add(new AliasSqlResult("SKIP", svc, ns, id, "SQL_TEXT_EMPTY"));

                // More specific warning when fallback is disabled.
                if (noFallback && (csvSqlText == null || csvSqlText.isBlank())) {
                    warningSink.warn(ConversionWarning.of(
                            WarningCode.CSV_SQL_TEXT_EMPTY_FALLBACK_DISABLED,
                            svc, ns, id,
                            "CSV sql_text empty and fallback disabled"
                    ));
                } else {
                    warningSink.warn(ConversionWarning.of(WarningCode.SQL_TEXT_EMPTY, svc, ns, id, "SQL text empty"));
                }

                if ((i + 1) % logEvery == 0 || (i + 1) == total) {
                    CliProgressMonitor.logProgress(i + 1, total, success, skip, tLoop0, key);
                }
                continue;
            }

            try {
                ConversionContext ctx = new ConversionContext(svc, ns, id);
                String transformedSql = generator.generate(sqlText, mode, ctx, warningSink);

                // ✅ POST-VALIDATION: AS-IS 잔존 검증(보존영역 제외)
                residueValidator.validate(transformedSql, ctx, warningSink, failFast);

                sqlOutputWriter.write(outputSqlDir, svc, ns, id, transformedSql);

                if (collectTobeMeta) {
                    TobeSqlAnalyzer.collectTobeSelectOutputs(transformedSql, svc, ns, id, tobeSelectOutputs);
                    TobeSqlAnalyzer.collectTobeDmlParams(transformedSql, svc, ns, id, tobeDmlParams);
                }

                success++;
                results.add(new AliasSqlResult("SUCCESS", svc, ns, id, ""));

            } catch (AsisResidueValidator.AsisResidueFailFastException e) {
                // failFast=true인 경우 의도적으로 중단
                skip++;
                results.add(new AliasSqlResult("SKIP", svc, ns, id, "ASIS_REMAINING_FAILFAST"));

                System.out.println("[FAILFAST] AS-IS residue detected: " + key);
                System.out.println("          " + safe(e.getMessage()));
                break;

            } catch (Exception e) {
                skip++;
                results.add(new AliasSqlResult("SKIP", svc, ns, id, e.getClass().getSimpleName()));
                warningSink.warn(new ConversionWarning(
                        WarningCode.TRANSFORM_ERROR, svc, ns, id,
                        e.getClass().getSimpleName(), safe(e.getMessage())
                ));

                System.out.println("[ERROR] transform/write failed: " + key);
                System.out.println("        ex=" + e.getClass().getName() + ": " + safe(e.getMessage()));
                e.printStackTrace(System.out);

                if (failFast) {
                    System.out.println("[FAILFAST] stop on first error.");
                    break;
                }
            }

            long oneMs = (System.nanoTime() - one0) / 1_000_000L;
            if (oneMs >= slowMs) {
                System.out.println("[SLOW] " + oneMs + "ms : " + key);
                warningSink.warn(new ConversionWarning(
                        WarningCode.SLOW_SQL, svc, ns, id,
                        "slowMs=" + slowMs + ", actualMs=" + oneMs, ""
                ));
            }

            if ((i + 1) % logEvery == 0 || (i + 1) == total) {
                CliProgressMonitor.logProgress(i + 1, total, success, skip, tLoop0, key);
            }
        }

        System.out.println("[STEP4] generating done. elapsed=" + ms(tLoop0) + "ms");
        System.out.println("[STAT] success=" + success + ", skip=" + skip);
        System.out.println("[STAT] warnings=" + warnings.size());

        if (!noResult) {
            long tXlsx0 = System.nanoTime();
            System.out.println("[STEP5] writing result xlsx... rows=" + results.size());
            resultWriter.write(resultXlsx, results, warnings, tobeSelectOutputs, tobeDmlParams);
            System.out.println("[STEP5] result xlsx written. elapsed=" + ms(tXlsx0) + "ms");
        } else {
            System.out.println("[STEP5] result xlsx skipped (--noResult). rows=" + results.size());
        }

        System.out.println("==================================================");
        System.out.println("[DONE] totalElapsed=" + ms(t0) + "ms");
        System.out.println("==================================================");
    }

    private static long ms(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000L;
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    // --- helper: firstNonBlank -------------------------------------------------
    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    // --- helper: mode check (enum name independent) ----------------------------
    private static boolean isTobeMode(AliasSqlGenerator.Mode mode) {
        if (mode == null) return true; // default TOBE
        String n = mode.name().toUpperCase();
        return n.contains("TOBE"); // TOBE / TOBE_SQL 등 모두 허용
    }

    // --- helper: file-or-dir resolver ------------------------------------------
// signature MUST match compile error:
// resolveFileOrDir(String, Path, String, String, String, String)
    private static Path resolveFileOrDir(
            String raw,
            Path defaultDir,
            String label,
            String preferred1,
            String preferred2,
            String legacyName
    ) {
        // 1) raw가 비어있으면 defaultDir에서 파일 탐색
        if (raw == null || raw.isBlank()) {
            return pickFromDir(defaultDir, label, preferred1, preferred2, legacyName);
        }

        Path p = Path.of(raw);
        // 상대경로면 현재 실행 디렉토리 기준으로 해석(보통 프로젝트 루트)
        p = p.toAbsolutePath().normalize();

        // 2) raw가 디렉토리면 그 내부에서 파일 탐색
        if (Files.exists(p) && Files.isDirectory(p)) {
            return pickFromDir(p, label, preferred1, preferred2, legacyName);
        }

        // 3) raw가 파일이면 그대로
        return p;
    }

    private static Path pickFromDir(Path dir, String label, String preferred1, String preferred2, String legacyName) {
        if (dir == null) {
            throw new IllegalArgumentException(label + " dir is null");
        }
        Path d = dir.toAbsolutePath().normalize();
        if (!Files.exists(d) || !Files.isDirectory(d)) {
            throw new IllegalArgumentException(label + " dir not found: " + d);
        }

        // 우선순위: preferred1 -> preferred2 -> legacyName
        for (String name : List.of(preferred1, preferred2, legacyName)) {
            if (name == null || name.isBlank()) continue;
            Path cand = d.resolve(name);
            if (Files.exists(cand) && Files.isRegularFile(cand)) return cand;
        }

        // 후보 파일 목록 출력(디버깅 도움)
        try {
            List<String> files = Files.list(d)
                    .filter(Files::isRegularFile)
                    .map(x -> x.getFileName().toString())
                    .sorted()
                    .toList();
            throw new IllegalArgumentException(label + " file not found in dir: " + d
                    + " | expected one of: " + preferred1 + ", " + preferred2 + ", " + legacyName
                    + " | candidates: " + files);
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " file not found in dir: " + d
                    + " | expected one of: " + preferred1 + ", " + preferred2 + ", " + legacyName, e);
        }
    }

    private static Path preferExistingDir(Path p1, Path p2) {
        if (p1 != null && Files.exists(p1) && Files.isDirectory(p1)) return p1;
        if (p2 != null && Files.exists(p2) && Files.isDirectory(p2)) return p2;
        return (p1 != null) ? p1 : p2;
    }
}
