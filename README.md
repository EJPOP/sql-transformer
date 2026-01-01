# sql-transformer (AS-IS → TO-BE SQL 변환기)

이 프로젝트는 **ServiceImpl → (xref CSV) → (Mapper SQL / CSV SQL)** 흐름으로 AS-IS SQL을 수집한 뒤,  
`mapping_column.xlsx`(ASIS↔TOBE 매핑) 기반으로 **테이블/컬럼을 치환**하고,  
**MyBatis 동적 태그(<if>, <choose> 등) 보존** 및 **Pretty(가독성 개선)** 를 적용하여 결과 `.sql`/`.xlsx`를 생성합니다.

> 목표: 공공기관 마이그레이션 환경에서 대량 SQL 변환을 **안전(오탐/파손 최소화)** 하게 수행

---

## 빠른 시작(Quick Start)

### 1) 빌드
```powershell
cd C:\sql-transformer
.\gradlew clean shadowJar
```

### 2) 실행(D:\run에서 jar 실행)
필요 폴더/파일 예시:
```text
D:\run
├─ sql-transformer-0.1.0-SNAPSHOT-all.jar
├─ mapping
│  ├─ column_mapping.xlsx
│  └─ service_sql_xref.csv
├─ systems
│  ├─ best
│  │  └─ sqls
│  └─ oasys
│     └─ sqls
└─ output
   └─ tobe-sql
```

실행 예시(best):
```powershell
cd D:\run
java "-Doasys.migration.baseDir=D:\run" `
     "-Doasys.migration.sqlsDir=systems\best\sqls" `
     -cp ".\sql-transformer-0.1.0-SNAPSHOT-all.jar" `
     oasys.migration.cli.AliasSqlGenerateCli `
     --mode=TOBE_SQL `
     --csv="mapping\service_sql_xref.csv" `
     --mapping="mapping\column_mapping.xlsx" `
     --out="output\tobe-sql" `
     --result="output\tobe-sql.xlsx"
```

---

## 현재 구조 요약(현행 패키지)

- `oasys.migration.app` : CLI 엔트리포인트/조립(Orchestration)
- `oasys.migration.cli` : CLI arg parsing / path resolve / progress monitor
- `oasys.migration.domain.*` : 변환(convert), 매핑(mapping), 모델(model), 출력(output), SQL 텍스트 공급(text)
- `oasys.migration.infra.*` : 파일/CSV/XLSX/Mapper XML I/O 구현체

---

## 반드시 지켜야 하는 “안전 규칙”(필수)

1) **MyBatis 태그/CDATA 보존**
- `<if>`, `<choose>`, `<when>`, `<otherwise>`, `<foreach>`, `<![CDATA[...]]>`는 **절대 파손 금지**
- 변환/Pretty 모두 “태그 자체는 그대로 복사”, 태그 사이 SQL만 변환 대상으로 처리

2) **AS-IS 잔존 검증(품질 게이트)**
- 변환 후 SQL에서 **AS-IS 테이블/컬럼 ID가 남아있으면 경고/실패**
- 운영 권장: `--failFast=true`로 강제 품질 확보

3) **Pretty는 의미를 바꾸지 않는다**
- INSERT 컬럼 리스트/VALUES 리스트의 콤마/순서/태그 위치 **재배치 금지**
- Pretty는 “절 키워드 개행/indent” 수준으로 제한(KISS)

---

## “책임 분리”가 특히 필요한 파일(우선순위)

SRP 위반 가능성이 높은 컴포넌트(라인 수/역할 혼합도 기준):

1) `domain.convert.DmlAnnotator` (≈1174 lines)
    - INSERT/UPDATE/DELETE 파싱 + 컬럼치환 + 삭제컬럼정책 + 주석/포맷까지 한 파일에 집중

2) `domain.convert.SelectRenderer` (≈871 lines)
    - SELECT 렌더링/절 분해/표현식 치환/코멘트/포맷 로직이 혼재

3) `domain.convert.TobeSqlAnalyzer` (≈550 lines)
    - 분석/수집/리포트 row 생성이 한 덩어리로 커짐 (SELECT output, DML param 등)

4) `callchain.ServiceSqlXrefLoader` vs `infra.callchain.ServiceSqlXrefLoader` (중복/혼재)
    - 도메인/인프라 경계 흐림 + 네이밍 중복

5) `app.AliasSqlGenerateCliApp` (≈456 lines)
    - 옵션/경로/조립/실행/로깅/통계가 한 클래스에 집중

---

## 제안: 패키지 구조(계층형 + 기능별 세분화)

“레이어(app/domain/infra)”는 유지하되, `domain.convert`를 기능 단위로 쪼개 SRP를 지키는 구조입니다.

```text
oasys.migration
├─ app
│  ├─ AliasSqlGenerateCliApp
│  ├─ usecase
│  │  └─ GenerateTobeSqlUseCase
│  └─ wiring
│     └─ AliasSqlGenerateComponentsFactory
│
├─ cli
│  ├─ CliArgParser
│  ├─ CliPathResolver
│  ├─ CliProgressMonitor
│  └─ CliReflectionUtil
│
├─ domain
│  ├─ model
│  ├─ mapping
│  │  ├─ ColumnMappingRegistry
│  │  └─ DeletedColumnPolicy
│  ├─ callchain
│  │  ├─ ServiceSqlCall
│  │  └─ ServiceSqlCallSource
│  ├─ output
│  │  ├─ SqlOutputWriter
│  │  ├─ ResultWriter
│  │  └─ SqlFileNamePolicy
│  └─ transform
│     ├─ scan
│     ├─ mybatis
│     ├─ alias
│     ├─ table
│     ├─ select
│     ├─ dml
│     │  ├─ DmlAnnotatorFacade
│     │  ├─ insert/ InsertDmlAnnotator
│     │  ├─ update/ UpdateDmlAnnotator
│     │  ├─ parse/  DmlListParser
│     │  ├─ comment/DmlCommentComposer
│     │  └─ format/ DmlFormatUtil
│     ├─ post
│     │  ├─ SqlPostProcessor
│     │  └─ SqlPrettifier
│     └─ validate
│        ├─ AsisResidueValidator
│        └─ TobeSqlAnalyzer
│
└─ infra
   ├─ mapping/ ColumnMappingXlsxLoader
   ├─ callchain/ CsvServiceSqlCallSource
   ├─ output/ FileSqlOutputWriter, XlsxResultWriter
   └─ text/ CsvFirstSqlTextProvider, MapperXmlRegistrySqlTextProvider
```

---

## 모듈별 책임(간략)

- `domain.transform.scan` : SQL 스캔/분해(문자열/주석/파라미터/태그 보호)
- `domain.transform.mybatis` : XML 태그/CDATA 보존(공통)
- `domain.transform.dml` : INSERT/UPDATE/DELETE 변환(구문별 worker로 분리)
- `domain.transform.select` : SELECT 변환(절 분해/표현식 치환/렌더링)
- `domain.transform.post` : Pretty/후처리(의미 변경 금지)
- `domain.transform.validate` : AS-IS 잔존 검증 + 리포트 수집

---

## 리팩터링 진행 순서(안전)

1) `DmlAnnotator` 내부 모델/유틸 승격 → `dml.model|format|comment`
2) `DmlListParser` 분리(콤마 split + 보호영역)
3) `InsertDmlAnnotator` / `UpdateDmlAnnotator` 분리 + Facade 위임
4) `SelectRenderer`를 clause split / expr transform / render 단계로 분리
5) `TobeSqlAnalyzer`를 Collector + RowFactory로 분리

---

## 운영 옵션(권장)

- Pretty OFF: `--pretty=false` 또는 `-Doasys.migration.pretty=false`
- 품질 게이트: `--failFast=true`
- fallback OFF: `--noFallback`
- 출력/리포트 OFF: `--noSqlOut`, `--noResult`
