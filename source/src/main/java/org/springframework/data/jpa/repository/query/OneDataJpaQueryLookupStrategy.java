/*
 * Copyright 2008-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jpa.repository.query;

import com.sinosoft.one.data.jade.annotation.SQL;
import com.sinosoft.one.data.jade.statement.StatementMetaData;

import java.lang.reflect.Method;

import javax.persistence.EntityManager;

import org.springframework.data.jpa.provider.PersistenceProvider;
import org.springframework.data.jpa.provider.QueryExtractor;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.core.NamedQueries;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.query.EvaluationContextProvider;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryLookupStrategy.Key;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.util.Assert;

/**
 * Query lookup strategy to execute finders.
 *
 * @author Oliver Gierke
 * @author Thomas Darimont
 */
public final class OneDataJpaQueryLookupStrategy {

  /**
   * Private constructor to prevent instantiation.
   */
  private OneDataJpaQueryLookupStrategy() {}

  /**
   * Base class for {@link QueryLookupStrategy} implementations that need access to an {@link EntityManager}.
   *
   * @author Oliver Gierke
   * @author Thomas Darimont
   */
  private abstract static class AbstractQueryLookupStrategy implements QueryLookupStrategy {

    private final EntityManager em;
    private final QueryExtractor provider;

    /**
     * Creates a new {@link AbstractQueryLookupStrategy}.
     *
     * @param em
     * @param extractor
     * @param evaluationContextProvider
     */
    public AbstractQueryLookupStrategy(EntityManager em, QueryExtractor extractor) {

      this.em = em;
      this.provider = extractor;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.repository.query.QueryLookupStrategy#resolveQuery(java.lang.reflect.Method, org.springframework.data.repository.core.RepositoryMetadata, org.springframework.data.projection.ProjectionFactory, org.springframework.data.repository.core.NamedQueries)
     */
    @Override
    public final RepositoryQuery resolveQuery(Method method, RepositoryMetadata metadata, ProjectionFactory factory,
                                              NamedQueries namedQueries) {
      return resolveQuery(new JpaQueryMethod(method, metadata, factory, provider), em, namedQueries);
    }

    protected abstract RepositoryQuery resolveQuery(JpaQueryMethod method, EntityManager em, NamedQueries namedQueries);
  }

  /**
   * {@link QueryLookupStrategy} to create a query from the method name.
   *
   * @author Oliver Gierke
   * @author Thomas Darimont
   */
  private static class CreateQueryLookupStrategy extends AbstractQueryLookupStrategy {

    private final PersistenceProvider persistenceProvider;

    public CreateQueryLookupStrategy(EntityManager em, QueryExtractor extractor) {

      super(em, extractor);
      this.persistenceProvider = PersistenceProvider.fromEntityManager(em);
    }

    @Override
    protected RepositoryQuery resolveQuery(JpaQueryMethod method, EntityManager em, NamedQueries namedQueries) {

      try {
        return new PartTreeJpaQuery(method, em, persistenceProvider);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format("Could not create query metamodel for method %s!", method.toString()), e);
      }
    }

  }

  /**
   * {@link QueryLookupStrategy} that tries to detect a declared query declared via {@link Query} annotation followed by
   * a JPA named query lookup.
   *
   * @author Oliver Gierke
   * @author Thomas Darimont
   */
  private static class DeclaredQueryLookupStrategy extends AbstractQueryLookupStrategy {

    private final EvaluationContextProvider evaluationContextProvider;

    /**
     * Creates a new {@link DeclaredQueryLookupStrategy}.
     *
     * @param em
     * @param extractor
     * @param evaluationContextProvider
     */
    public DeclaredQueryLookupStrategy(EntityManager em, QueryExtractor extractor,
                                       EvaluationContextProvider evaluationContextProvider) {

      super(em, extractor);
      this.evaluationContextProvider = evaluationContextProvider;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.data.jpa.repository.query.JpaQueryLookupStrategy.AbstractQueryLookupStrategy#resolveQuery(org.springframework.data.jpa.repository.query.JpaQueryMethod, javax.persistence.EntityManager, org.springframework.data.repository.core.NamedQueries)
     */
    @Override
    protected RepositoryQuery resolveQuery(JpaQueryMethod method, EntityManager em, NamedQueries namedQueries) {

      RepositoryQuery query = JpaQueryFactory.INSTANCE.fromQueryAnnotation(method, em, evaluationContextProvider);

      if (null != query) {
        return query;
      }

      query = JpaQueryFactory.INSTANCE.fromProcedureAnnotation(method, em);

      if (null != query) {
        return query;
      }

      String name = method.getNamedQueryName();
      if (namedQueries.hasQuery(name)) {
        return JpaQueryFactory.INSTANCE.fromMethodWithQueryString(method, em, namedQueries.getQuery(name),
                                                                  evaluationContextProvider);
      }

      query = NamedQuery.lookupFrom(method, em);

      if (null != query) {
        return query;
      }

      throw new IllegalStateException(
          String.format("Did neither find a NamedQuery nor an annotated query for method %s!", method));
    }
  }

  /**
   * {@link QueryLookupStrategy} to try to detect a declared query first (
   * {@link org.springframework.data.jpa.repository.Query}, JPA named query). In case none is found we fall back on
   * query creation.
   *
   * @author Oliver Gierke
   * @author Thomas Darimont
   */
  private static class CreateIfNotFoundQueryLookupStrategy implements QueryLookupStrategy {

    private final EntityManager em;
    private final DeclaredQueryLookupStrategy lookupStrategy;
    private final CreateQueryLookupStrategy createStrategy;


    public CreateIfNotFoundQueryLookupStrategy(EntityManager em,
                                               CreateQueryLookupStrategy createStrategy,
                                               DeclaredQueryLookupStrategy lookupStrategy) {
      this.em = em;
      this.createStrategy = createStrategy;
      this.lookupStrategy = lookupStrategy;
    }

    @Override
    public RepositoryQuery resolveQuery(Method method,
                                        RepositoryMetadata metadata,
                                        ProjectionFactory factory,
                                        NamedQueries namedQueries) {
      if (canResolveSqlQuery(method)){
        return resolveSqlQuery(method, metadata, factory, namedQueries);
      }
      try {
        return lookupStrategy.resolveQuery(method, metadata, factory, namedQueries);
      } catch (IllegalStateException e) {
        return createStrategy.resolveQuery(method, metadata, factory, namedQueries);
      }
    }

    private boolean canResolveSqlQuery(Method method){
      return method.getAnnotation(SQL.class) != null;
    }

    private final RepositoryQuery resolveSqlQuery(Method method, RepositoryMetadata metadata, ProjectionFactory factory, NamedQueries namedQueries) {
      Method m = method;
      String sqlValue = m.getAnnotation(SQL.class).value();
      if (sqlValue == null || sqlValue.length() == 0){
        throw new IllegalArgumentException(String.format("Did not find an value of annotated SQL for method %s!", method));
      }
      if (StatementMetaData.CALL_PATTERN.matcher(sqlValue).find()) {
        if (m.getReturnType() != Void.TYPE) {
          throw new IllegalArgumentException(String.format(
              "It requires a 'Void type' for the procedure method: %s!", method));
        }
      }
      return new OneDataRepositoryQuery(method, metadata, factory, em);
    }
  }

  /**
   * Creates a {@link QueryLookupStrategy} for the given {@link EntityManager} and {@link Key}.
   *
   * @param em must not be {@literal null}.
   * @param key may be {@literal null}.
   * @param extractor must not be {@literal null}.
   * @param evaluationContextProvider must not be {@literal null}.
   * @return
   */
  public static QueryLookupStrategy create(EntityManager em, Key key, QueryExtractor extractor,
                                           EvaluationContextProvider evaluationContextProvider) {

    Assert.notNull(em, "EntityManager must not be null!");
    Assert.notNull(extractor, "QueryExtractor must not be null!");
    Assert.notNull(evaluationContextProvider, "EvaluationContextProvider must not be null!");

    switch (key != null ? key : Key.CREATE_IF_NOT_FOUND) {
      case CREATE:
        return new CreateQueryLookupStrategy(em, extractor);
      case USE_DECLARED_QUERY:
        return new DeclaredQueryLookupStrategy(em, extractor, evaluationContextProvider);
      case CREATE_IF_NOT_FOUND:
        return new CreateIfNotFoundQueryLookupStrategy(em,
                                                       new CreateQueryLookupStrategy(em, extractor),
                                                       new DeclaredQueryLookupStrategy(em, extractor, evaluationContextProvider));
      default:
        throw new IllegalArgumentException(String.format("Unsupported query lookup strategy %s!", key));
    }
  }
}
