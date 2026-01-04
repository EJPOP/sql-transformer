package app;

import domain.callchain.ServiceSqlCall;
import domain.convert.AliasSqlGenerator;
import domain.mapping.ColumnMappingRegistry;
import domain.output.ResultWriter;
import domain.output.SqlOutputWriter;
import domain.text.SqlTextProvider;
import domain.text.SqlTextSource;
import infra.callchain.ServiceSqlXrefLoader;
import infra.output.*;
import infra.text.CsvFirstSqlTextProvider;
import infra.text.CsvOnlySqlTextProvider;
import infra.text.XmlFirstSqlTextProvider;
import infra.text.XmlOnlySqlTextProvider;
import mybatis.SqlStatementRegistry;

import java.nio.file.Path;
import java.util.List;

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

    SqlTextProvider createSqlTextProvider(SqlStatementRegistry registry, SqlTextSource source) {
        if (source == null) source = SqlTextSource.CSV_FIRST;

        return switch (source) {
            case CSV -> new CsvOnlySqlTextProvider();
            case CSV_FIRST -> new CsvFirstSqlTextProvider(registry);
            case XML -> new XmlOnlySqlTextProvider(registry);
            case XML_FIRST -> new XmlFirstSqlTextProvider(registry);
        };
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
        return new FileSqlOutputWriter(new AliasSqlFileWriter());
    }

    ResultWriter createResultWriter(boolean enable) {
        if (!enable) return new NullResultWriter();
        return new XlsxResultWriter(new AliasSqlResultXlsxWriter());
    }
}