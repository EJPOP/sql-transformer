# sql-transformer 패키지/소스 구조 요약 (한글 번역본)
- 생성일: 2026-01-01 12:13
- 기준: `src/main/java` (현재 ZIP 기준)

---

## 패키지 레이아웃(정석 구조)
```text
src/main/java/
  cli/
  app/
  domain/
    callchain/
    convert/
    mapping/
    model/
    output/
    text/
  infra/
    callchain/
    mapping/
    output/
    text/
  mybatis/
```

---

## 패키지 역할 요약
| 패키지 | 역할 |
|---|---|
| `cli` | 변환 실행을 위한 CLI 진입점(메인) 및 인자 파싱 |
| `app` | 애플리케이션 오케스트레이션: 컴포넌트 조립(wiring), 변환 파이프라인 실행, 출력/결과 관리 |
| `domain.callchain` | 콜체인 도메인 모델(서비스 ↔ 매퍼 ↔ sqlId) 및 정규화 |
| `domain.convert` | SQL 변환 로직(SELECT/DML 변환, 별칭(alias) 처리, 검증기 등) |
| `domain.mapping` | 테이블/컬럼 매핑의 도메인 표현 및 조회(lookup) 유틸 |
| `domain.model` | 변환 단계 전반에서 공통으로 사용하는 핵심 Request/Result/Trace 모델 |
| `domain.output` | IO와 분리된 출력 정책(파일명 규칙, 그룹핑 규칙 등) |
| `domain.text` | (namespace, sqlId)로 SQL 원문 텍스트를 해석/조회하는 추상화 |
| `infra.callchain` | CSV/XLSX에서 콜체인을 로딩하여 도메인 모델로 변환/정규화 |
| `infra.mapping` | mapping_column XLSX/CSV를 로딩하여 `domain.mapping` 레지스트리에 적재 |
| `infra.text` | 콜체인/레지스트리를 이용해 SQL 원문을 해석(예: CSV 우선 + 폴백) |
| `infra.output` | 생성된 SQL 파일 및 결과 리포트(XLSX 등)를 파일시스템에 저장 |
| `mybatis` | MyBatis 전용 처리: mapper XML 로더/파서, 태그(select/insert/update/delete) 기반 유틸 |

---

## 패키지별 소스 파일 인덱스

### `app`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `AliasSqlGenerateCliApp.java` | `app/AliasSqlGenerateCliApp.java` | CLI 애플리케이션 실행 진입점(내부적으로 `AliasSqlGenerateCli`에서 호출). |
| `AliasSqlGenerateComponentsFactory.java` | `app/AliasSqlGenerateComponentsFactory.java` | `AliasSqlGenerateCliApp` 구동에 필요한 객체들을 조립하는 팩토리. |

### `cli`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `AliasSqlGenerateCli.java` | `cli/AliasSqlGenerateCli.java` | CLI 진입점(외부에서 실행되는 facade). |
| `CliArgParser.java` | `cli/CliArgParser.java` | CLI 인자 파싱 헬퍼. |
| `CliPathResolver.java` | `cli/CliPathResolver.java` | CLI 실행 경로/시스템 경로 해석기(baseDir/system/sqlsDir 등). |
| `CliProgressMonitor.java` | `cli/CliProgressMonitor.java` | 장시간 작업용 진행률/하트비트(heartbeat) 로그 출력. |
| `CliReflectionUtil.java` | `cli/CliReflectionUtil.java` | 레거시 DTO 필드를 컴파일 타임 결합 없이 읽기 위한 리플렉션 유틸. |
| `MigrationVerifyCli.java` | `cli/MigrationVerifyCli.java` | (향후 확장용) 마이그레이션 검증 플로우를 위한 CLI 플레이스홀더. |

### `domain.callchain`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `ServiceSqlCall.java` | `domain/callchain/ServiceSqlCall.java` | (CSV에 해당 컬럼이 있을 때) 서비스↔SQL 호출 정보를 담는 모델. |

### `domain.convert`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `AliasSqlGenerator.java` | `domain/convert/AliasSqlGenerator.java` | 하위호환을 위한 퍼사드(facade). |
| `AliasSqlGeneratorEngine.java` | `domain/convert/AliasSqlGeneratorEngine.java` | `AliasSqlGenerator`의 내부 구현체. |
| `AsisResidueValidator.java` | `domain/convert/AsisResidueValidator.java` | AS-IS 잔존(미변환) 패턴 검증. |
| `BareColumnClauseConverter.java` | `domain/convert/BareColumnClauseConverter.java` | TOBE 모드에서만 적용되는 폴백(fallback) 변환(단독 컬럼 절 처리 등). |
| `CommentColumnAligner.java` | `domain/convert/CommentColumnAligner.java` | 표현식 시작 위치 기준으로 블록 주석을 고정 컬럼에 정렬. |
| `CommentExtractor.java` | `domain/convert/CommentExtractor.java` | 변환 시 주석을 안정적으로 재부착하기 위해 SQL 구간에서 주석 본문을 추출. |
| `DmlAnnotator.java` | `domain/convert/DmlAnnotator.java` | DML에 대한 주석/주해(annotate) 및 포맷 처리. |
| `FromJoinAliasResolver.java` | `domain/convert/FromJoinAliasResolver.java` | FROM/JOIN 절에서 alias → tableId 맵을 best-effort로 수집. |
| `GluedKeywordFixer.java` | `domain/convert/GluedKeywordFixer.java` | 공백/개행 손실로 절 키워드가 이전 토큰에 붙는(glued) 케이스를 보정. |
| `ParamRenamer.java` | `domain/convert/ParamRenamer.java` | 컬럼 매핑을 기반으로 MyBatis `#PARAM#` 이름 변경을 추론/적용. |
| `QualifiedColumnRefConverter.java` | `domain/convert/QualifiedColumnRefConverter.java` | 테이블/별칭이 붙은 컬럼 참조(qualified ref) 변환 처리. |
| `SelectLineTransformer.java` | `domain/convert/SelectLineTransformer.java` | SELECT 라인 변환의 공개(public) 진입점. |
| `SelectLineTransformerCore.java` | `domain/convert/SelectLineTransformerCore.java` | 공개 진입점을 작게 유지하기 위한 패키지-프라이빗 오케스트레이터. |
| `SelectRenderer.java` | `domain/convert/SelectRenderer.java` | SELECT 리스트 변환 렌더러. |
| `SqlIdentifierUtil.java` | `domain/convert/SqlIdentifierUtil.java` | 변환기들이 공유하는 식별자(identifier) 유틸. |
| `SqlPostProcessor.java` | `domain/convert/SqlPostProcessor.java` | 메인 변환 이후 적용되는 후처리 단계 모음. |
| `SqlPrettifier.java` | `domain/convert/SqlPrettifier.java` | 매우 보수적인 SQL 포매터(정리기). |
| `SqlScan.java` | `domain/convert/SqlScan.java` | ✅ MERGE 경계: `WHEN MATCHED` / `WHEN NOT`만 종료로 인정(CASE WHEN 보호). |
| `SqlSegmentTransformer.java` | `domain/convert/SqlSegmentTransformer.java` | SQL 문자열 내 모든 SELECT를 찾아 SELECT-body 구간만 재작성. |
| `SqlStatement.java` | `domain/convert/SqlStatement.java` | SQL 문(statement) 표현 모델(내부 표현). |
| `SqlTopLevelSplitter.java` | `domain/convert/SqlTopLevelSplitter.java` | 문자열/주석/파라미터를 무시하고 최상위 depth에서만 콤마/equals 기준 분리. |
| `TableIdConverter.java` | `domain/convert/TableIdConverter.java` | TOBE 모드: 현행 테이블ID를 TOBE 테이블ID로 치환. |
| `TobeSqlAnalyzer.java` | `domain/convert/TobeSqlAnalyzer.java` | 변환된 SQL로부터 추가 TOBE 메타데이터를 도출(분석 목적). |

### `domain.mapping`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `ColumnMapping.java` | `domain/mapping/ColumnMapping.java` | 컬럼 매핑 1행(row) 모델. |
| `ColumnMappingRegistry.java` | `domain/mapping/ColumnMappingRegistry.java` | `column_mapping.xlsx`에서 로딩된 컬럼 매핑 레지스트리. |

### `domain.model`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `AliasSqlResult.java` | `domain/model/AliasSqlResult.java` | 리포트용 변환 결과 1행(row) 모델. |
| `ConversionContext.java` | `domain/model/ConversionContext.java` | SQL 1건 변환의 컨텍스트(경고/출처 매핑에 사용). |
| `ConversionWarning.java` | `domain/model/ConversionWarning.java` | 변환 과정에서 발생한 경고 1건. |
| `ConversionWarningSink.java` | `domain/model/ConversionWarningSink.java` | 경고 수집(sink) 인터페이스. |
| `ListConversionWarningSink.java` | `domain/model/ListConversionWarningSink.java` | 리스트 기반 sink + best-effort 중복 제거. |
| `NullConversionWarningSink.java` | `domain/model/NullConversionWarningSink.java` | No-op(비활성화용) 경고 sink. |
| `TobeDmlParamRow.java` | `domain/model/TobeDmlParamRow.java` | TOBE DML 파라미터 리포트/분석용 row 모델. |
| `TobeSelectOutputRow.java` | `domain/model/TobeSelectOutputRow.java` | TOBE SELECT 출력 컬럼 리포트/분석용 row 모델. |
| `WarningCode.java` | `domain/model/WarningCode.java` | 변환/리포트에 사용하는 표준 경고 코드. |

### `domain.output`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `ResultWriter.java` | `domain/output/ResultWriter.java` | 결과 리포트를 저장하는 책임(인터페이스). |
| `ServicePackageUnit.java` | `domain/output/ServicePackageUnit.java` | 출력 폴더 키(서비스 패키지 단위) 생성기. |
| `SqlFileNamePolicy.java` | `domain/output/SqlFileNamePolicy.java` | 생성 SQL 파일명 정책. |
| `SqlOutputWriter.java` | `domain/output/SqlOutputWriter.java` | 변환 SQL을 파일로 내보내는 책임(인터페이스). |

### `domain.text`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `SqlTextProvider.java` | `domain/text/SqlTextProvider.java` | (namespace, sqlId)로 SQL 텍스트를 제공하는 책임을 캡슐화. |
| `SqlTextResolution.java` | `domain/text/SqlTextResolution.java` | SqlTextProvider의 결과(텍스트 + 폴백 여부/출처 메타데이터). |

### `infra.callchain`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `ServiceSqlXrefLoader.java` | `infra/callchain/ServiceSqlXrefLoader.java` | `service_sql_xref.csv` 로더. |

### `infra.mapping`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `ColumnMappingCsvLoader.java` | `infra/mapping/ColumnMappingCsvLoader.java` | 컬럼 매핑 CSV 로더. |
| `ColumnMappingXlsxLoader.java` | `infra/mapping/ColumnMappingXlsxLoader.java` | 컬럼 매핑 XLSX 로더(헤더 스킵 등 포함). |

### `infra.output`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `AliasSqlFileWriter.java` | `infra/output/AliasSqlFileWriter.java` | 저수준(low-level) SQL 파일 라이터. |
| `AliasSqlResultXlsxWriter.java` | `infra/output/AliasSqlResultXlsxWriter.java` | XLSX 리포트 라이터. |
| `FileSqlOutputWriter.java` | `infra/output/FileSqlOutputWriter.java` | 파일 저장 기반 `SqlOutputWriter` 구현체. |
| `NullResultWriter.java` | `infra/output/NullResultWriter.java` | No-op 결과 라이터(기능 토글용). |
| `NullSqlOutputWriter.java` | `infra/output/NullSqlOutputWriter.java` | No-op SQL 출력 라이터(기능 토글용). |
| `XlsxResultWriter.java` | `infra/output/XlsxResultWriter.java` | `AliasSqlResultXlsxWriter` 기반 XLSX 결과 출력 구현체(호환 유지). |

### `infra.text`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `CsvFirstSqlTextProvider.java` | `infra/text/CsvFirstSqlTextProvider.java` | CSV 우선 조회 + Registry 폴백(fallback) 구현체. |
| `CsvOnlySqlTextProvider.java` | `infra/text/CsvOnlySqlTextProvider.java` | CSV 전용 SQL 텍스트 제공 구현체. |

### `mybatis`
| 파일 | 경로 | 설명(요약) |
|---|---|---|
| `MapperXmlIndex.java` | `mybatis/MapperXmlIndex.java` | 매퍼 XML 인덱스(※ `Files.walk()` 반환 순서는 환경에 따라 달라질 수 있음). |
| `MapperXmlLoader.java` | `mybatis/MapperXmlLoader.java` | 매퍼 XML 로더(🔒 외부 DTD/엔티티 완전 차단: 망분리/보안 대응). |
| `SqlsDirectoryScanner.java` | `mybatis/SqlsDirectoryScanner.java` | 해석된 sqls 디렉토리 하위에서 mapper XML 파일을 스캔. |
| `SqlStatementRegistry.java` | `mybatis/SqlStatementRegistry.java` | (namespace, id) 기반 SQL statement 레지스트리. |
