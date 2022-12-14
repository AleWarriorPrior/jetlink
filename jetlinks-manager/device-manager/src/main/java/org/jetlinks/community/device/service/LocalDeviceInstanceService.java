package org.jetlinks.community.device.service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.ezorm.rdb.mapping.ReactiveUpdate;
import org.hswebframework.ezorm.rdb.mapping.defaults.SaveResult;
import org.hswebframework.ezorm.rdb.operator.dml.Terms;
import org.hswebframework.web.crud.events.EntityDeletedEvent;
import org.hswebframework.web.crud.events.EntityEventHelper;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.community.device.entity.*;
import org.jetlinks.community.device.enums.DeviceFeature;
import org.jetlinks.community.device.enums.DeviceState;
import org.jetlinks.community.device.response.DeviceDeployResult;
import org.jetlinks.community.device.response.DeviceDetail;
import org.jetlinks.community.utils.ErrorUtils;
import org.jetlinks.core.device.DeviceConfigKey;
import org.jetlinks.core.device.DeviceOperator;
import org.jetlinks.core.device.DeviceRegistry;
import org.jetlinks.core.enums.ErrorCode;
import org.jetlinks.core.exception.DeviceOperationException;
import org.jetlinks.core.message.DeviceMessageReply;
import org.jetlinks.core.message.FunctionInvokeMessageSender;
import org.jetlinks.core.message.WritePropertyMessageSender;
import org.jetlinks.core.message.function.FunctionInvokeMessageReply;
import org.jetlinks.core.message.property.ReadPropertyMessageReply;
import org.jetlinks.core.message.property.WritePropertyMessageReply;
import org.jetlinks.core.metadata.ConfigMetadata;
import org.jetlinks.core.metadata.PropertyMetadata;
import org.jetlinks.core.metadata.types.StringType;
import org.jetlinks.core.utils.CyclicDependencyChecker;
import org.reactivestreams.Publisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LocalDeviceInstanceService extends GenericReactiveCrudService<DeviceInstanceEntity, String> {

    private final DeviceRegistry registry;

    private final LocalDeviceProductService deviceProductService;

    private final DeviceConfigMetadataManager metadataManager;

    @SuppressWarnings("all")
    private final ReactiveRepository<DeviceTagEntity, String> tagRepository;

    public LocalDeviceInstanceService(DeviceRegistry registry,
                                      LocalDeviceProductService deviceProductService,
                                      DeviceConfigMetadataManager metadataManager,
                                      @SuppressWarnings("all")
                                      ReactiveRepository<DeviceTagEntity, String> tagRepository) {
        this.registry = registry;
        this.deviceProductService = deviceProductService;
        this.metadataManager = metadataManager;
        this.tagRepository = tagRepository;
    }


    @Override
    public Mono<SaveResult> save(Publisher<DeviceInstanceEntity> entityPublisher) {
        return Flux.from(entityPublisher)
                   .doOnNext(instance -> instance.setState(null))
                   .as(super::save);
    }


    /**
     * ??????????????????
     *
     * @param deviceId ??????ID
     * @return ??????????????????
     * @since 1.2
     */
    public Mono<Map<String, Object>> resetConfiguration(String deviceId) {
        return this
            .findById(deviceId)
            .zipWhen(device -> deviceProductService.findById(device.getProductId()))
            .flatMap(tp2 -> {
                DeviceProductEntity product = tp2.getT2();
                DeviceInstanceEntity device = tp2.getT1();
                return Mono
                    .defer(() -> {
                        if (MapUtils.isNotEmpty(product.getConfiguration())) {
                            if (MapUtils.isNotEmpty(device.getConfiguration())) {
                                product.getConfiguration()
                                       .keySet()
                                       .forEach(device.getConfiguration()::remove);
                            }
                            //??????????????????????????????
                            return registry.getDevice(deviceId)
                                           .flatMap(opts -> opts.removeConfigs(product.getConfiguration().keySet()))
                                           .then();
                        }
                        return Mono.empty();
                    }).then(
                        //???????????????
                        createUpdate()
                            .set(device::getConfiguration)
                            .where(device::getId)
                            .execute()
                    )
                    .thenReturn(device.getConfiguration());
            })
            .defaultIfEmpty(Collections.emptyMap())
            ;
    }

    /**
     * ?????????????????????????????????
     *
     * @param id ??????ID
     * @return ????????????
     */
    public Mono<DeviceDeployResult> deploy(String id) {
        return findById(id)
            .flux()
            .as(this::deploy)
            .singleOrEmpty();
    }

    /**
     * ???????????????????????????????????????
     *
     * @param flux ???????????????
     * @return ????????????
     */
    public Flux<DeviceDeployResult> deploy(Flux<DeviceInstanceEntity> flux) {
        return flux
            .flatMap(instance -> registry
                .register(instance.toDeviceInfo())
                .flatMap(deviceOperator -> deviceOperator
                    .getState()
                    .flatMap(r -> {
                        if (r.equals(org.jetlinks.core.device.DeviceState.unknown) ||
                            r.equals(org.jetlinks.core.device.DeviceState.noActive)) {
                            instance.setState(DeviceState.offline);
                            return deviceOperator.putState(org.jetlinks.core.device.DeviceState.offline);
                        }
                        instance.setState(DeviceState.of(r));
                        return Mono.just(true);
                    })
                    .flatMap(success -> success ? Mono.just(deviceOperator) : Mono.empty())
                )
                .thenReturn(instance))
            .buffer(50)
            .publishOn(Schedulers.single())
            .flatMap(all -> Flux
                .fromIterable(all)
                .groupBy(DeviceInstanceEntity::getState)
                .flatMap(group -> group
                    .map(DeviceInstanceEntity::getId)
                    .collectList()
                    .flatMap(list -> createUpdate()
                        .where()
                        .set(DeviceInstanceEntity::getState, group.key())
                        .set(DeviceInstanceEntity::getRegistryTime, new Date())
                        .in(DeviceInstanceEntity::getId, list)
                        .execute()
                        .map(r -> DeviceDeployResult.success(list.size()))
                        .onErrorResume(err -> Mono.just(DeviceDeployResult.error(err.getMessage()))))))
            ;
    }

    /**
     * ????????????(????????????),?????????,??????????????????????????????. ????????????????????????????????????????????????.
     *
     * @param id ??????ID
     * @return ????????????
     */
    public Mono<Integer> cancelDeploy(String id) {
        return findById(Mono.just(id))
            .flatMap(product -> registry
                .unregisterDevice(id)
                .then(createUpdate()
                          .set(DeviceInstanceEntity::getState, DeviceState.notActive.getValue())
                          .where(DeviceInstanceEntity::getId, id)
                          .execute()));
    }

    /**
     * ????????????,?????????,??????????????????????????????. ????????????????????????????????????????????????.
     *
     * @param id ??????ID
     * @return ????????????
     */
    public Mono<Integer> unregisterDevice(String id) {
        return this.findById(Mono.just(id))
                   .flatMap(device -> registry
                       .unregisterDevice(id)
                       .then(createUpdate()
                                 .set(DeviceInstanceEntity::getState, DeviceState.notActive.getValue())
                                 .where(DeviceInstanceEntity::getId, id)
                                 .execute()));
    }

    /**
     * ??????????????????
     *
     * @param ids ??????ID
     * @return ????????????
     */
    public Mono<Integer> unregisterDevice(Publisher<String> ids) {
        return Flux.from(ids)
                   .flatMap(id -> registry.unregisterDevice(id).thenReturn(id))
                   .collectList()
                   .flatMap(list -> createUpdate()
                       .set(DeviceInstanceEntity::getState, DeviceState.notActive.getValue())
                       .where().in(DeviceInstanceEntity::getId, list)
                       .execute());
    }

    protected Mono<DeviceDetail> createDeviceDetail(DeviceProductEntity product,
                                                    DeviceInstanceEntity device,
                                                    List<DeviceTagEntity> tags) {

        DeviceDetail detail = new DeviceDetail().with(product).with(device).with(tags);
        return Mono
            .zip(
                //????????????
                registry
                    .getDevice(device.getId())
                    .flatMap(operator -> operator
                        //???????????????????????????,??????????????????????????????,???????????????????????????????????????.
                        .checkState()
                        .map(DeviceState::of)
                        //????????????,?????????????????????
                        .onErrorReturn(device.getState())
                        //?????????????????????,????????????????????????????????????
                        .filter(state -> state != detail.getState())
                        .doOnNext(detail::setState)
                        .flatMap(state -> createUpdate()
                            .set(DeviceInstanceEntity::getState, state)
                            .where(DeviceInstanceEntity::getId, device.getId())
                            .execute())
                        .thenReturn(operator)),
                //????????????
                metadataManager
                    .getDeviceConfigMetadata(device.getId())
                    .flatMapIterable(ConfigMetadata::getProperties)
                    .collectList(),
                detail::with
            )
            //??????????????????
            .flatMap(Function.identity())
            .switchIfEmpty(
                Mono.defer(() -> {
                    //?????????????????????????????????????????????,??????????????????????????????????????????.
                    //???????????????????????????????????????,?????????????????????????????????.
                    if (detail.getState() != DeviceState.notActive) {
                        return createUpdate()
                            .set(DeviceInstanceEntity::getState, DeviceState.notActive)
                            .where(DeviceInstanceEntity::getId, detail.getId())
                            .execute()
                            .thenReturn(detail.notActive());
                    }
                    return Mono.just(detail.notActive());
                }).thenReturn(detail))
            .onErrorResume(err -> {
                log.warn("get device detail error", err);
                return Mono.just(detail);
            });

    }

    public Mono<DeviceDetail> getDeviceDetail(String deviceId) {
        return this
            .findById(deviceId)
            .zipWhen(device -> deviceProductService.findById(device.getProductId()))//????????????
            .zipWith(tagRepository
                         .createQuery()
                         .where(DeviceTagEntity::getDeviceId, deviceId)
                         .fetch()
                         .collectList()
                         .defaultIfEmpty(Collections.emptyList()) //????????????
                , (left, right) -> Tuples.of(left.getT2(), left.getT1(), right))
            .flatMap(tp3 -> createDeviceDetail(tp3.getT1(), tp3.getT2(), tp3.getT3()));
    }

    public Mono<DeviceState> getDeviceState(String deviceId) {
        return registry
            .getDevice(deviceId)
            .flatMap(DeviceOperator::checkState)
            .flatMap(state -> {
                DeviceState deviceState = DeviceState.of(state);
                return this
                    .createUpdate()
                    .set(DeviceInstanceEntity::getState, deviceState)
                    .where(DeviceInstanceEntity::getId, deviceId)
                    .execute()
                    .thenReturn(deviceState);
            })
            .defaultIfEmpty(DeviceState.notActive);
    }

    public Flux<List<DeviceStateInfo>> syncStateBatch(Flux<List<String>> batch, boolean force) {

        return batch
            .concatMap(list -> Flux
                .fromIterable(list)
                .publishOn(Schedulers.parallel())
                .flatMap(id -> registry
                    .getDevice(id)
                    .flatMap(operator -> {
                        Mono<Byte> state = force
                            ? operator
                            .checkState()
                            .onErrorResume(err -> operator.getState())
                            : operator.getState();
                        return Mono
                            .zip(
                                state.defaultIfEmpty(org.jetlinks.core.device.DeviceState.offline),//??????
                                Mono.just(operator.getDeviceId()), //??????id
                                operator.getConfig(DeviceConfigKey.isGatewayDevice).defaultIfEmpty(false)//?????????????????????
                            );
                    })
                    //???????????????????????????????????????????????????.
                    .defaultIfEmpty(Tuples.of(org.jetlinks.core.device.DeviceState.noActive, id, false)))
                .collect(Collectors.groupingBy(Tuple2::getT1))
                .flatMapIterable(Map::entrySet)
                .flatMap(group -> {
                    List<String> deviceIdList = group
                        .getValue()
                        .stream()
                        .map(Tuple3::getT2)
                        .collect(Collectors.toList());
                    DeviceState state = DeviceState.of(group.getKey());
                    return
                        //????????????????????????
                        getRepository()
                            .createUpdate()
                            .set(DeviceInstanceEntity::getState, state)
                            .where()
                            .in(DeviceInstanceEntity::getId, deviceIdList)
                            .when(state != DeviceState.notActive, where -> where.not(DeviceInstanceEntity::getState, DeviceState.notActive))
                            .execute()
                            .thenReturn(group.getValue().size())
                            .then(Mono.just(
                                deviceIdList
                                    .stream()
                                    .map(id -> DeviceStateInfo.of(id, state))
                                    .collect(Collectors.toList())
                            ));
                }))
            //???????????????????????????
            .as(EntityEventHelper::setDoNotFireEvent);
    }

    private static <R extends DeviceMessageReply, T> Function<R, Mono<T>> mapReply(Function<R, T> function) {
        return reply -> {
            if (ErrorCode.REQUEST_HANDLING.name().equals(reply.getCode())) {
                throw new DeviceOperationException(ErrorCode.REQUEST_HANDLING, reply.getMessage());
            }
            if (!reply.isSuccess()) {
                throw new BusinessException(reply.getMessage(), reply.getCode());
            }
            return Mono.justOrEmpty(function.apply(reply));
        };
    }

    //????????????????????????
    @SneakyThrows
    public Mono<DevicePropertiesEntity> readAndConvertProperty(String deviceId,
                                                               String property) {
        return registry
            .getDevice(deviceId)
            .switchIfEmpty(ErrorUtils.notFound("???????????????"))
            .flatMap(deviceOperator -> deviceOperator
                .messageSender()
                .readProperty(property)
                .messageId(IDGenerator.SNOW_FLAKE_STRING.generate())
                .send()
                .flatMap(mapReply(ReadPropertyMessageReply::getProperties))
                .reduceWith(LinkedHashMap::new, (main, map) -> {
                    main.putAll(map);
                    return main;
                })
                .flatMap(map -> {
                    Object value = map.get(property);
                    return deviceOperator
                        .getMetadata()
                        .map(deviceMetadata -> deviceMetadata
                            .getProperty(property)
                            .map(PropertyMetadata::getValueType)
                            .orElse(new StringType()))
                        .map(dataType -> DevicePropertiesEntity
                            .builder()
                            .deviceId(deviceId)
                            .productId(property)
                            .build()
                            .withValue(dataType, value));
                }));

    }

    //??????????????????
    @SneakyThrows
    public Mono<Map<String, Object>> writeProperties(String deviceId,
                                                     Map<String, Object> properties) {

        return registry
            .getDevice(deviceId)
            .switchIfEmpty(ErrorUtils.notFound("???????????????"))
            .map(operator -> operator
                .messageSender()
                .writeProperty()
                .messageId(IDGenerator.SNOW_FLAKE_STRING.generate())
                .write(properties)
            )
            .flatMapMany(WritePropertyMessageSender::send)
            .flatMap(mapReply(WritePropertyMessageReply::getProperties))
            .reduceWith(LinkedHashMap::new, (main, map) -> {
                main.putAll(map);
                return main;
            });
    }

    //??????????????????
    @SneakyThrows
    public Flux<?> invokeFunction(String deviceId,
                                  String functionId,
                                  Map<String, Object> properties) {
        return registry
            .getDevice(deviceId)
            .switchIfEmpty(ErrorUtils.notFound("???????????????"))
            .flatMap(operator -> operator
                .messageSender()
                .invokeFunction(functionId)
                .messageId(IDGenerator.SNOW_FLAKE_STRING.generate())
                .setParameter(properties)
                .validate()
            )
            .flatMapMany(FunctionInvokeMessageSender::send)
            .flatMap(mapReply(FunctionInvokeMessageReply::getOutput));
    }

    private final CyclicDependencyChecker<DeviceInstanceEntity, Void> checker = CyclicDependencyChecker
        .of(DeviceInstanceEntity::getId, DeviceInstanceEntity::getParentId, this::findById);

    public Mono<Void> checkCyclicDependency(DeviceInstanceEntity device) {
        return checker.check(device);
    }

    public Mono<Void> checkCyclicDependency(String id, String parentId) {
        DeviceInstanceEntity instance = new DeviceInstanceEntity();
        instance.setId(id);
        instance.setParentId(parentId);
        return checker.check(instance);
    }

    public Mono<Void> mergeConfiguration(String deviceId,
                                         Map<String, Object> configuration,
                                         Function<ReactiveUpdate<DeviceInstanceEntity>,
                                             ReactiveUpdate<DeviceInstanceEntity>> updateOperation) {
        if (MapUtils.isEmpty(configuration)) {
            return Mono.empty();
        }
        return this
            .findById(deviceId)
            .flatMap(device -> {
                //??????????????????
                device.mergeConfiguration(configuration);
                return createUpdate()
                    .set(device::getConfiguration)
                    .set(device::getFeatures)
                    .set(device::getDeriveMetadata)
                    .as(updateOperation)
                    .where(device::getId)
                    .execute();
            })
            .then(
                //????????????????????????
                registry
                    .getDevice(deviceId)
                    .flatMap(device -> device.setConfigs(configuration))
            )
            .then();

    }

    /**
     * ????????????????????????,????????????????????????,?????????????????????????????????????????????.
     */
    private Flux<Void> deletedHandle(Flux<DeviceInstanceEntity> devices) {
        return devices.filter(device -> !StringUtils.isEmpty(device.getParentId()))
            .groupBy(DeviceInstanceEntity::getParentId)
            .flatMap(group -> {
                String parentId = group.key();
                return group.flatMap(child -> registry.getDevice(child.getId())
                            .flatMap(device -> device.removeConfig(DeviceConfigKey.parentGatewayId.getKey()).thenReturn(device))
                    )
                    .as(childrenDeviceOp -> registry.getDevice(parentId)
                        .flatMap(gwOperator -> gwOperator.getProtocol()
                            .flatMap(protocolSupport -> protocolSupport.onChildUnbind(gwOperator, childrenDeviceOp))
                        )
                    );
            })
            // ????????????
            .thenMany(
                devices.filter(device -> device.getState() != DeviceState.notActive)
                    .flatMap(device -> registry.unregisterDevice(device.getId()))
            );
    }

    @EventListener
    public void deletedHandle(EntityDeletedEvent<DeviceInstanceEntity> event) {
        event.async(
            this.deletedHandle(Flux.fromIterable(event.getEntity())).then()
        );
    }
}
