package app;

import domain.callchain.ServiceSqlCall;

import infra.callchain.ServiceSqlXrefLoader;

import mybatis.SqlStatementRegistry;

import java.nio.file.Path;

import java.util.List;

import domain.convert.AliasSqlGenerator;

import domain.mapping.ColumnMappingRegistry;

import domain.output.ResultWriter;

import domain.output.SqlOutputWriter;

import domain.text.SqlTextProvider;

import infra.output.AliasSqlFileWriter;

import infra.output.AliasSqlResultXlsxWriter;

import infra.output.FileSqlOutputWriter;

import infra.output.NullResultWriter;

import infra.output.NullSqlOutputWriter;

import infra.output.XlsxResultWriter;

import mybatis.SqlStatementRegistry;

import infra.text.CsvFirstSqlTextProvider;

import infra.text.CsvOnlySqlTextProvider;

/**
 * Object-assembly factory for {@link AliasSqlGenerateCliApp}.
 * <p>
 * Goal: keep the CLI app focused on orchestration/logging and move object
 * creation ("new") and initialization concerns here.
 */
final class AliasSqlGenerateComponentsFactory {

    SqlStatementRegistry createSqlRegistry(boolean enable) {
        if (!enable) return null;
        SqlStatementRegistry registry = new SqlStatementRegistry();
        registry.initialize();
        return registry;
    }

    SqlTextProvider createSqlTextProvider(SqlStatementRegistry registry, boolean enableFallback) {
        if (!enableFallback) return new CsvOnlySqlTextProvider();
        return new CsvFirstSqlTextProvider(registry);
    }

    ColumnMappingRegistry createColumnMappingRegistry(Path mappingXlsxPath) {
        return new ColumnMappingRegistry(mappingXlsxPath.toString());
    }

    List<ServiceSqlCall> loadCalls(Path callchainCsvPath) {
        ServiceSqlXrefLoader loader = new ServiceSqlXrefLoader();
        return loader.load(callchainCsvPath.toString());
    }

    AliasSqlGenerator createGenerator(ColumnMappingRegistry columnMappingRegistry) {
        return new AliasSqlGenerator(columnMappingRegistry);
    }

    SqlOutputWriter createSqlOutputWriter(boolean enable) {
        if (!enable) return new NullSqlOutputWriter();
        // Wrap the existing concrete writer to keep behavior unchanged.
        return new FileSqlOutputWriter(new AliasSqlFileWriter());
    }

    ResultWriter createResultWriter(boolean enable) {
        if (!enable) return new NullResultWriter();
        // Wrap the existing concrete writer to keep behavior unchanged.
        return new XlsxResultWriter(new AliasSqlResultXlsxWriter());
    }
}