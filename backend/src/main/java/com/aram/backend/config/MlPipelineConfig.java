package com.aram.backend.config;

import com.aram.backend.catalog.CatalogRepository;
import com.aram.backend.catalog.SourceSignalRepository;
import com.aram.backend.ml.MlPipelineService;
import com.aram.backend.ml.MlSql;
import com.aram.backend.ml.MlWarehouse;
import com.aram.backend.service.TagCatalogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Clock;

@Configuration
@ConditionalOnProperty(prefix = "aram.ml", name = "enabled", havingValue = "true")
public class MlPipelineConfig {

	@Bean
	public MlPipelineService mlPipelineService(MlWarehouse warehouse, SourceSignalRepository signals, CatalogRepository catalog,
			TagCatalogService taxonomy, AramProperties properties, Clock clock,
			com.aram.backend.service.CuratedBookCatalog books, com.aram.backend.catalog.BookSignalRepository bookSignals) {
		AramProperties.Export export = properties.getExport();
		AramProperties.Ml ml = properties.getMl();
		String project = StringUtils.hasText(export.getProjectId()) ? export.getProjectId() : properties.getFirestore().getProjectId();
		MlSql.Names names = new MlSql.Names(project, export.getLocation(), export.getDataset(), ml.getDataset(),
				ml.getEmbeddingModel(), ml.getEmbeddingEndpoint(), ml.getConnection());
		return new MlPipelineService(warehouse, signals, catalog, taxonomy, ml, names, clock, books, bookSignals);
	}
}
