package io.github.mongoshaman.core.repositories;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.pushEach;
import static com.mongodb.client.model.Updates.set;
import static io.github.mongoshaman.core.domain.meta.MigrationFileMeta.CHECKSUM;
import static io.github.mongoshaman.core.domain.meta.MigrationFileMeta.CONTENT;
import static io.github.mongoshaman.core.domain.meta.MigrationFileMeta.NAME;
import static io.github.mongoshaman.core.domain.meta.MigrationFileMeta.ORDER;
import static io.github.mongoshaman.core.domain.meta.MigrationFileMeta.STATUS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.collections4.CollectionUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;

import io.github.mongoshaman.core.configuration.ShamanConfiguration;
import io.github.mongoshaman.core.domain.Execution;
import io.github.mongoshaman.core.domain.MigrationFile;
import io.github.mongoshaman.core.domain.statuses.MigrationStatus;

public class ShamanRepository {

  private static final Logger log = LoggerFactory.getLogger(ShamanRepository.class);
  private MongoCollection<Document> collection;

  private MongoDatabase mongoDatabase;

  public ShamanRepository(final MongoClient mongoClient, final ShamanConfiguration configuration) {
    this.mongoDatabase = mongoClient.getDatabase(configuration.getDatabaseName());
    collection = initCollection(configuration);
  }

  private MongoCollection<Document> initCollection(final ShamanConfiguration configuration) {
    final String collectionName = configuration.getCollectionName();
    if (StreamSupport.stream(mongoDatabase.listCollectionNames().spliterator(), false)
        .noneMatch(collectionName::equals)) {
      mongoDatabase.createCollection(collectionName);
      traceCollectionCreation(collectionName);
    }
    return mongoDatabase.getCollection(collectionName);
  }

  public MongoDatabase getMongoDatabase() {
    return mongoDatabase;
  }

  public Set<MigrationFile> findAll() {
    traceFilter(new Document());

    final Set<Document> results = collection.find().into(new HashSet<>());
    traceFindResult(results);

    return Optional.ofNullable(results).orElse(new HashSet<>()).stream().map(this::documentToMigrationFile)
        .collect(Collectors.toSet());
  }

  public void save(final MigrationFile migrationFile) {
    collection.updateOne(filterByName(migrationFile), combine(migrationFileToUpdateBson(migrationFile)),
        new UpdateOptions().upsert(true));
  }

  public void updateStatus(final MigrationFile file, final MigrationStatus status) {
    collection.updateOne(filterByName(file), set(STATUS, status.name()));
  }

  public void updateExecutionStatus(final Execution execution) {
    final Bson filter =
        and(filterByName(execution.getFile()), eq("executions.number", execution.getStatement().getLineNumber()));
    final List<Bson> updates = new ArrayList<>();
    updates.add(set("executions.$.status", execution.getStatus().name()));
    if(Objects.nonNull(execution.getException())) {
      updates.add(set("executions.$.exception", execution.getException().getLocalizedMessage()));
    }
    traceFilter(filter);
    collection.updateOne(filter, combine(updates));
  }

  private List<Bson> migrationFileToUpdateBson(final MigrationFile file) {
    return Arrays.asList(set(NAME, file.getName()), set(ORDER, file.getOrder()), set(CONTENT, file.getContent()),
        set(CHECKSUM, file.getChecksum()), set(STATUS, file.getStatus().name()),
        pushEach("executions",
            file.getExecutions().stream()
                .map(execution -> new Document().append("number", execution.getStatement().getLineNumber())
                    .append("code", execution.getStatement().getCode()).append(STATUS, execution.getStatus().name()))
                .collect(Collectors.toList())));
  }

  private Bson filterByName(MigrationFile migrationFile) {
    return eq(NAME, migrationFile.getName());
  }

  private MigrationFile documentToMigrationFile(final Document document) {
    final MigrationFile migrationFile = new MigrationFile();
    migrationFile.setName((String) document.get(NAME));
    migrationFile.setOrder((Integer) document.get(ORDER));
    migrationFile.setContent((String) document.get(CONTENT));
    migrationFile.setChecksum((String) document.get(CHECKSUM));
    migrationFile.setStatus(MigrationStatus.valueOf((String) document.get(STATUS)));

    return migrationFile;
  }

  private void traceFindResult(Object o) {
    if (log.isTraceEnabled()) {
      if (Objects.isNull(o) || o instanceof Collection && CollectionUtils.isEmpty((Collection) o)) {
        log.trace("No query results");
      } else {

        log.trace("Query results: {}", o);
      }
    }
  }

  private void traceFilter(final Bson filter) {
    if (log.isTraceEnabled()) {
      log.trace("Querying for {} filtering by {}", collection.getNamespace().getCollectionName(), filter);
    }
  }

  private void traceCollectionCreation(String collectionName) {
    if (log.isTraceEnabled()) {
      log.trace("{} collection has been created", collectionName);
    }
  }
}
