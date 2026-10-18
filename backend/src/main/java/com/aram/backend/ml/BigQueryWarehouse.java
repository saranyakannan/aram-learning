package com.aram.backend.ml;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.StandardSQLTypeName;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "aram.ml", name = "enabled", havingValue = "true")
public class BigQueryWarehouse implements MlWarehouse {

	private final BigQuery bigQuery;

	public BigQueryWarehouse(@Qualifier("mlBigQuery") BigQuery bigQuery) {
		this.bigQuery = bigQuery;
	}

	@Override
	public void execute(String sql) {
		run(sql);
	}

	@Override
	public void execute(String sql, Map<String, List<?>> arrayParameters) {
		var builder = QueryJobConfiguration.newBuilder(sql).setUseLegacySql(false);
		arrayParameters.forEach((name, values) -> {
			boolean numbers = !values.isEmpty() && values.get(0) instanceof Number;
			builder.addNamedParameter(name, numbers
					? QueryParameterValue.array(values.stream().map(v -> ((Number) v).longValue()).toArray(Long[]::new), StandardSQLTypeName.INT64)
					: QueryParameterValue.array(values.stream().map(String::valueOf).toArray(String[]::new), StandardSQLTypeName.STRING));
		});
		try {
			bigQuery.query(builder.build());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for BigQuery", e);
		}
	}

	@Override
	public List<Map<String, String>> query(String sql) {
		var result = run(sql);
		List<Map<String, String>> rows = new ArrayList<>();
		var fields = result.getSchema() == null ? List.<com.google.cloud.bigquery.Field>of() : result.getSchema().getFields();
		for (FieldValueList row : result.iterateAll()) {
			Map<String, String> map = new LinkedHashMap<>();
			for (var field : fields) {
				FieldValue value = row.get(field.getName());
				map.put(field.getName(), value.isNull() ? null : value.getStringValue());
			}
			rows.add(map);
		}
		return rows;
	}

	private com.google.cloud.bigquery.TableResult run(String sql) {
		try {
			return bigQuery.query(QueryJobConfiguration.newBuilder(sql).setUseLegacySql(false).build());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for BigQuery", e);
		}
	}
}
