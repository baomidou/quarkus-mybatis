package io.quarkiverse.mybatis.deployment;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.javassist.util.proxy.ProxyFactory;
import org.apache.ibatis.logging.log4j.Log4jImpl;
import org.apache.ibatis.scripting.defaults.RawLanguageDriver;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.type.EnumTypeHandler;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import io.quarkiverse.mybatis.runtime.MyBatisProducers;
import io.quarkiverse.mybatis.runtime.MyBatisRecorder;
import io.quarkiverse.mybatis.runtime.config.MyBatisDataSourceRuntimeConfig;
import io.quarkiverse.mybatis.runtime.config.MyBatisRuntimeConfig;
import io.quarkiverse.mybatis.runtime.meta.MapperDataSource;
import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.configuration.ConfigurationError;

class MyBatisProcessor {

    private static final Logger LOG = Logger.getLogger(MyBatisProcessor.class);
    private static final String FEATURE = "mybatis";
    private static final DotName MYBATIS_MAPPER = DotName.createSimple(Mapper.class.getName());
    private static final DotName MYBATIS_TYPE_HANDLER = DotName.createSimple(MappedTypes.class.getName());
    private static final DotName MYBATIS_JDBC_TYPE_HANDLER = DotName.createSimple(MappedJdbcTypes.class.getName());
    private static final DotName MYBATIS_MAPPER_DATA_SOURCE = DotName.createSimple(MapperDataSource.class.getName());

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void runtimeInitialzed(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInit) {
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(Log4jImpl.class.getName()));
    }

    @BuildStep
    void reflectiveClasses(BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {
        reflectiveClass.produce(new ReflectiveClassBuildItem(false, false,
                ProxyFactory.class,
                XMLLanguageDriver.class,
                RawLanguageDriver.class,
                SelectProvider.class,
                UpdateProvider.class,
                InsertProvider.class,
                DeleteProvider.class,
                Result.class,
                Results.class,
                ResultType.class,
                ResultMap.class,
                EnumTypeHandler.class));

        reflectiveClass.produce(new ReflectiveClassBuildItem(true, true,
                PerpetualCache.class, LruCache.class));
    }

    @BuildStep
    void addMyBatisMappers(BuildProducer<MyBatisMapperBuildItem> mappers,
            BuildProducer<ReflectiveClassBuildItem> reflective,
            BuildProducer<NativeImageProxyDefinitionBuildItem> proxy,
            CombinedIndexBuildItem indexBuildItem) {
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(MYBATIS_MAPPER)) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                reflective.produce(new ReflectiveClassBuildItem(true, false, dotName.toString()));
                proxy.produce(new NativeImageProxyDefinitionBuildItem(dotName.toString()));

                Optional<AnnotationInstance> mapperDatasource = i.target().asClass().annotations().entrySet().stream()
                        .filter(entry -> entry.getKey().equals(MYBATIS_MAPPER_DATA_SOURCE))
                        .map(Map.Entry::getValue)
                        .map(annotationList -> annotationList.get(0))
                        .findFirst();
                if (mapperDatasource.isPresent()) {
                    String dataSourceName = mapperDatasource.get().value().asString();
                    mappers.produce(new MyBatisMapperBuildItem(dotName, dataSourceName));
                } else {
                    mappers.produce(new MyBatisMapperBuildItem(dotName, null));
                }
            }
        }
    }

    @BuildStep
    void addMyBatisMappedTypes(BuildProducer<MyBatisMappedTypeBuildItem> mappedTypes,
            BuildProducer<MyBatisMappedJdbcTypeBuildItem> mappedJdbcTypes,
            CombinedIndexBuildItem indexBuildItem) {
        List<DotName> names = new ArrayList<>();
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(MYBATIS_TYPE_HANDLER)) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                mappedTypes.produce(new MyBatisMappedTypeBuildItem(dotName));
                names.add(dotName);
            }
        }
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(MYBATIS_JDBC_TYPE_HANDLER)) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                DotName dotName = i.target().asClass().name();
                if (!names.contains(dotName)) {
                    mappedJdbcTypes.produce(new MyBatisMappedJdbcTypeBuildItem(dotName));
                }
            }
        }
    }

    @BuildStep
    void unremovableBeans(BuildProducer<AdditionalBeanBuildItem> beanProducer) {
        beanProducer.produce(AdditionalBeanBuildItem.unremovableOf(MyBatisProducers.class));
    }

    @BuildStep
    void initialSql(BuildProducer<NativeImageResourceBuildItem> resource, MyBatisRuntimeConfig config) {
        config.initialSql.ifPresent(initialSql -> resource.produce(new NativeImageResourceBuildItem(initialSql)));
        config.dataSources.values().forEach(dataSource -> dataSource.initialSql
                .ifPresent(initialSql -> resource.produce(new NativeImageResourceBuildItem(initialSql))));
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void generateSqlSessionFactory(MyBatisRuntimeConfig myBatisRuntimeConfig,
            List<MyBatisMapperBuildItem> myBatisMapperBuildItems,
            List<MyBatisMappedTypeBuildItem> myBatisMappedTypeBuildItems,
            List<MyBatisMappedJdbcTypeBuildItem> myBatisMappedJdbcTypeBuildItems,
            List<JdbcDataSourceBuildItem> jdbcDataSourcesBuildItem,
            BuildProducer<SqlSessionFactoryBuildItem> sqlSessionFactory,
            MyBatisRecorder recorder) {
        List<String> mappers = myBatisMapperBuildItems
                .stream().map(m -> m.getMapperName().toString()).collect(Collectors.toList());
        List<String> mappedTypes = myBatisMappedTypeBuildItems
                .stream().map(m -> m.getMappedTypeName().toString()).collect(Collectors.toList());
        List<String> mappedJdbcTypes = myBatisMappedJdbcTypeBuildItems
                .stream().map(m -> m.getMappedJdbcTypeName().toString()).collect(Collectors.toList());

        List<Pair<String, Boolean>> dataSources = new ArrayList<>();
        if (myBatisRuntimeConfig.dataSource.isPresent()) {
            String dataSourceName = myBatisRuntimeConfig.dataSource.get();
            Optional<JdbcDataSourceBuildItem> jdbcDataSourceBuildItem = jdbcDataSourcesBuildItem.stream()
                    .filter(i -> i.getName().equals(dataSourceName))
                    .findFirst();
            if (!jdbcDataSourceBuildItem.isPresent()) {
                throw new ConfigurationError("Can not find datasource " + dataSourceName);
            }
            dataSources.add(Pair.of(dataSourceName, true));
        } else {
            dataSources = jdbcDataSourcesBuildItem.stream()
                    .map(dataSource -> Pair.of(dataSource.getName(), dataSource.isDefault()))
                    .collect(Collectors.toList());
            if (dataSources.isEmpty()) {
                throw new ConfigurationError("No datasource found");
            }
        }

        dataSources.forEach(dataSource -> {
            MyBatisDataSourceRuntimeConfig dataSourceConfig = myBatisRuntimeConfig.dataSources.get(dataSource.getKey());
            sqlSessionFactory.produce(
                    new SqlSessionFactoryBuildItem(
                            recorder.createSqlSessionFactory(
                                    myBatisRuntimeConfig,
                                    dataSourceConfig,
                                    dataSource.getKey(),
                                    mappers,
                                    mappedTypes,
                                    mappedJdbcTypes),
                            dataSource.getKey(), dataSource.getValue()));
        });
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void generateSqlSessionManager(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems,
            BuildProducer<SqlSessionManagerBuildItem> sqlSessionManager,
            MyBatisRecorder recorder) {
        sqlSessionFactoryBuildItems.forEach(sessionFactory -> sqlSessionManager.produce(
                new SqlSessionManagerBuildItem(
                        recorder.createSqlSessionManager(sessionFactory.getSqlSessionFactory()),
                        sessionFactory.getDataSourceName(),
                        sessionFactory.isDefaultDataSource())));
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void generateMapperBeans(MyBatisRecorder recorder,
            List<MyBatisMapperBuildItem> myBatisMapperBuildItems,
            List<MyBatisMappedTypeBuildItem> myBatisMappedTypesBuildItems,
            List<MyBatisMappedJdbcTypeBuildItem> myBatisMappedJdbcTypesBuildItems,
            List<SqlSessionManagerBuildItem> sqlSessionManagerBuildItems,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItemBuildProducer) {
        Map<String, SqlSessionManagerBuildItem> dataSourceToSessionManagerBuildItem = sqlSessionManagerBuildItems.stream()
                .collect(Collectors.toMap(SqlSessionManagerBuildItem::getDataSourceName, Function.identity()));
        SqlSessionManagerBuildItem defaultSqlSessionManagerBuildItem = getDefaultSessionManager(sqlSessionManagerBuildItems);
        for (MyBatisMapperBuildItem i : myBatisMapperBuildItems) {
            SqlSessionManagerBuildItem sessionManagerBuildItem;
            if (i.getDataSourceName() == null) {
                if (defaultSqlSessionManagerBuildItem.isDefaultDataSource() || sqlSessionManagerBuildItems.size() == 1) {
                    sessionManagerBuildItem = defaultSqlSessionManagerBuildItem;
                } else {
                    throw new ConfigurationError("Could not choose data source for mapper: " + i.getMapperName() +
                            ". Please use @MapperDataSource annotation for specified the mapper class");
                }
            } else {
                sessionManagerBuildItem = dataSourceToSessionManagerBuildItem.get(i.getDataSourceName());
                if (sessionManagerBuildItem == null) {
                    throw new ConfigurationError(String.format("Data source %s does not exist", i.getDataSourceName()));
                }
            }
            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(i.getMapperName())
                    .scope(Singleton.class)
                    .setRuntimeInit()
                    .unremovable()
                    .supplier(recorder.MyBatisMapperSupplier(i.getMapperName().toString(),
                            sessionManagerBuildItem.getSqlSessionManager()));
            syntheticBeanBuildItemBuildProducer.produce(configurator.done());
        }
        for (MyBatisMappedTypeBuildItem i : myBatisMappedTypesBuildItems) {
            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(i.getMappedTypeName())
                    .scope(Singleton.class)
                    .setRuntimeInit()
                    .unremovable()
                    .supplier(recorder.MyBatisMappedTypeSupplier(i.getMappedTypeName().toString(),
                            defaultSqlSessionManagerBuildItem.getSqlSessionManager()));
            syntheticBeanBuildItemBuildProducer.produce(configurator.done());
        }
        for (MyBatisMappedJdbcTypeBuildItem i : myBatisMappedJdbcTypesBuildItems) {
            SyntheticBeanBuildItem.ExtendedBeanConfigurator configurator = SyntheticBeanBuildItem
                    .configure(i.getMappedJdbcTypeName())
                    .scope(Singleton.class)
                    .setRuntimeInit()
                    .unremovable()
                    .supplier(recorder.MyBatisMappedJdbcTypeSupplier(i.getMappedJdbcTypeName().toString(),
                            defaultSqlSessionManagerBuildItem.getSqlSessionManager()));
            syntheticBeanBuildItemBuildProducer.produce(configurator.done());
        }
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void register(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems,
            BeanContainerBuildItem beanContainerBuildItem,
            MyBatisRecorder recorder) {
        recorder.register(getDefaultSessionFactory(sqlSessionFactoryBuildItems).getSqlSessionFactory(),
                beanContainerBuildItem.getValue());
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void runInitialSql(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems,
            MyBatisRuntimeConfig myBatisRuntimeConfig,
            MyBatisRecorder recorder) {
        sqlSessionFactoryBuildItems.forEach(sqlSessionFactoryBuildItem -> {
            MyBatisDataSourceRuntimeConfig dataSourceConfig = myBatisRuntimeConfig.dataSources
                    .get(sqlSessionFactoryBuildItem.getDataSourceName());
            Optional<String> optionalInitialSql;
            if (sqlSessionFactoryBuildItem.isDefaultDataSource() || sqlSessionFactoryBuildItems.size() == 1) {
                optionalInitialSql = dataSourceConfig != null && dataSourceConfig.initialSql.isPresent()
                        ? dataSourceConfig.initialSql
                        : myBatisRuntimeConfig.initialSql;
            } else {
                optionalInitialSql = dataSourceConfig != null ? dataSourceConfig.initialSql : Optional.empty();
            }
            optionalInitialSql.ifPresent(initialSql -> recorder.runInitialSql(
                    sqlSessionFactoryBuildItem.getSqlSessionFactory(), initialSql));
        });
    }

    private SqlSessionManagerBuildItem getDefaultSessionManager(List<SqlSessionManagerBuildItem> sqlSessionManagerBuildItems) {
        return sqlSessionManagerBuildItems.stream()
                .filter(SqlSessionManagerBuildItem::isDefaultDataSource)
                .findFirst()
                .orElse(sqlSessionManagerBuildItems.get(0));
    }

    private SqlSessionFactoryBuildItem getDefaultSessionFactory(List<SqlSessionFactoryBuildItem> sqlSessionFactoryBuildItems) {
        return sqlSessionFactoryBuildItems.stream()
                .filter(SqlSessionFactoryBuildItem::isDefaultDataSource)
                .findFirst()
                .orElse(sqlSessionFactoryBuildItems.get(0));
    }
}
