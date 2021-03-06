/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.transform.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.ClientHelper;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.transform.TransformMessages;
import org.elasticsearch.xpack.core.transform.action.StartTransformAction;
import org.elasticsearch.xpack.core.transform.transforms.Transform;
import org.elasticsearch.xpack.core.transform.transforms.TransformConfig;
import org.elasticsearch.xpack.core.transform.transforms.TransformState;
import org.elasticsearch.xpack.core.transform.transforms.TransformTaskState;
import org.elasticsearch.xpack.transform.notifications.DataFrameAuditor;
import org.elasticsearch.xpack.transform.persistence.DataFrameTransformsConfigManager;
import org.elasticsearch.xpack.transform.persistence.DataframeIndex;
import org.elasticsearch.xpack.transform.transforms.SourceDestValidator;
import org.elasticsearch.xpack.transform.transforms.pivot.Pivot;

import java.io.IOException;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.elasticsearch.xpack.core.transform.TransformMessages.DATA_FRAME_CANNOT_START_FAILED_TRANSFORM;

public class TransportStartDataFrameTransformAction extends
    TransportMasterNodeAction<StartTransformAction.Request, StartTransformAction.Response> {

    private static final Logger logger = LogManager.getLogger(TransportStartDataFrameTransformAction.class);
    private final XPackLicenseState licenseState;
    private final DataFrameTransformsConfigManager dataFrameTransformsConfigManager;
    private final PersistentTasksService persistentTasksService;
    private final Client client;
    private final DataFrameAuditor auditor;

    @Inject
    public TransportStartDataFrameTransformAction(TransportService transportService, ActionFilters actionFilters,
                                                  ClusterService clusterService, XPackLicenseState licenseState,
                                                  ThreadPool threadPool, IndexNameExpressionResolver indexNameExpressionResolver,
                                                  DataFrameTransformsConfigManager dataFrameTransformsConfigManager,
                                                  PersistentTasksService persistentTasksService, Client client,
                                                  DataFrameAuditor auditor) {
        super(StartTransformAction.NAME, transportService, clusterService, threadPool, actionFilters,
                StartTransformAction.Request::new, indexNameExpressionResolver);
        this.licenseState = licenseState;
        this.dataFrameTransformsConfigManager = dataFrameTransformsConfigManager;
        this.persistentTasksService = persistentTasksService;
        this.client = client;
        this.auditor = auditor;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected StartTransformAction.Response read(StreamInput in) throws IOException {
        return new StartTransformAction.Response(in);
    }

    @Override
    protected void masterOperation(Task ignoredTask, StartTransformAction.Request request,
                                   ClusterState state,
                                   ActionListener<StartTransformAction.Response> listener) throws Exception {
        if (!licenseState.isDataFrameAllowed()) {
            listener.onFailure(LicenseUtils.newComplianceException(XPackField.Transform));
            return;
        }
        final AtomicReference<Transform> transformTaskHolder = new AtomicReference<>();

        // <4> Wait for the allocated task's state to STARTED
        ActionListener<PersistentTasksCustomMetaData.PersistentTask<Transform>> newPersistentTaskActionListener =
            ActionListener.wrap(
                task -> {
                    Transform transformTask = transformTaskHolder.get();
                    assert transformTask != null;
                    waitForDataFrameTaskStarted(task.getId(),
                        transformTask,
                        request.timeout(),
                        ActionListener.wrap(
                            taskStarted -> listener.onResponse(new StartTransformAction.Response(true)),
                            listener::onFailure));
            },
            listener::onFailure
        );

        // <3> Create the task in cluster state so that it will start executing on the node
        ActionListener<Void> createOrGetIndexListener = ActionListener.wrap(
            unused -> {
                Transform transformTask = transformTaskHolder.get();
                assert transformTask != null;
                PersistentTasksCustomMetaData.PersistentTask<Transform> existingTask =
                    getExistingTask(transformTask.getId(), state);
                if (existingTask == null) {
                    // Create the allocated task and wait for it to be started
                    persistentTasksService.sendStartRequest(transformTask.getId(),
                        Transform.NAME,
                        transformTask,
                        newPersistentTaskActionListener);
                } else {
                    TransformState transformState = (TransformState)existingTask.getState();
                    if(transformState.getTaskState() == TransformTaskState.FAILED) {
                        listener.onFailure(new ElasticsearchStatusException(
                            TransformMessages.getMessage(DATA_FRAME_CANNOT_START_FAILED_TRANSFORM,
                                request.getId(),
                                transformState.getReason()),
                            RestStatus.CONFLICT));
                    } else {
                        // If the task already exists that means that it is either running or failed
                        // Since it is not failed, that means it is running, we return a conflict.
                        listener.onFailure(new ElasticsearchStatusException(
                            "Cannot start transform [{}] as it is already started.",
                            RestStatus.CONFLICT,
                            request.getId()
                        ));
                    }
                }
            },
            listener::onFailure
        );

        // <2> If the destination index exists, start the task, otherwise deduce our mappings for the destination index and create it
        ActionListener<TransformConfig> getTransformListener = ActionListener.wrap(
            config -> {
                if (config.isValid() == false) {
                    listener.onFailure(new ElasticsearchStatusException(
                        TransformMessages.getMessage(TransformMessages.DATA_FRAME_CONFIG_INVALID, request.getId()),
                        RestStatus.BAD_REQUEST
                    ));
                    return;
                }
                // Validate source and destination indices
                SourceDestValidator.validate(config, clusterService.state(), indexNameExpressionResolver, false);

                transformTaskHolder.set(createDataFrameTransform(config.getId(), config.getVersion(), config.getFrequency()));
                final String destinationIndex = config.getDestination().getIndex();
                String[] dest = indexNameExpressionResolver.concreteIndexNames(state,
                    IndicesOptions.lenientExpandOpen(),
                    destinationIndex);

                if(dest.length == 0) {
                    auditor.info(request.getId(),
                        "Creating destination index [" +  destinationIndex + "] with deduced mappings.");
                    createDestinationIndex(config, createOrGetIndexListener);
                } else {
                    auditor.info(request.getId(), "Using existing destination index [" + destinationIndex + "].");
                    ClientHelper.executeAsyncWithOrigin(client.threadPool().getThreadContext(),
                        ClientHelper.DATA_FRAME_ORIGIN,
                        client.admin()
                            .indices()
                            .prepareStats(dest)
                            .clear()
                            .setDocs(true)
                            .request(),
                        ActionListener.<IndicesStatsResponse>wrap(
                            r -> {
                                long docTotal = r.getTotal().docs.getCount();
                                if (docTotal > 0L) {
                                    auditor.warning(request.getId(), "Non-empty destination index [" + destinationIndex + "]. " +
                                        "Contains [" + docTotal + "] total documents.");
                                }
                                createOrGetIndexListener.onResponse(null);
                            },
                            e -> {
                                String msg = "Unable to determine destination index stats, error: " + e.getMessage();
                                logger.error(msg, e);
                                auditor.warning(request.getId(), msg);
                                createOrGetIndexListener.onResponse(null);
                            }),
                        client.admin().indices()::stats);
                }
            },
            listener::onFailure
        );

        // <1> Get the config to verify it exists and is valid
        dataFrameTransformsConfigManager.getTransformConfiguration(request.getId(), getTransformListener);
    }

    private void createDestinationIndex(final TransformConfig config, final ActionListener<Void> listener) {

        final Pivot pivot = new Pivot(config.getPivotConfig());

        ActionListener<Map<String, String>> deduceMappingsListener = ActionListener.wrap(
            mappings -> DataframeIndex.createDestinationIndex(
                client,
                Clock.systemUTC(),
                config,
                mappings,
                ActionListener.wrap(r -> listener.onResponse(null), listener::onFailure)),
            deduceTargetMappingsException -> listener.onFailure(
                new RuntimeException(TransformMessages.REST_PUT_DATA_FRAME_FAILED_TO_DEDUCE_DEST_MAPPINGS,
                    deduceTargetMappingsException))
        );

        pivot.deduceMappings(client, config.getSource(), deduceMappingsListener);
    }

    @Override
    protected ClusterBlockException checkBlock(StartTransformAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    private static Transform createDataFrameTransform(String transformId, Version transformVersion, TimeValue frequency) {
        return new Transform(transformId, transformVersion, frequency);
    }

    @SuppressWarnings("unchecked")
    private static PersistentTasksCustomMetaData.PersistentTask<Transform> getExistingTask(String id, ClusterState state) {
        PersistentTasksCustomMetaData pTasksMeta = state.getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
        if (pTasksMeta == null) {
            return null;
        }
        Collection<PersistentTasksCustomMetaData.PersistentTask<?>> existingTask = pTasksMeta.findTasks(Transform.NAME,
            t -> t.getId().equals(id));
        if (existingTask.isEmpty()) {
            return null;
        } else {
            assert(existingTask.size() == 1);
            PersistentTasksCustomMetaData.PersistentTask<?> pTask = existingTask.iterator().next();
            if (pTask.getParams() instanceof Transform) {
                return (PersistentTasksCustomMetaData.PersistentTask<Transform>)pTask;
            }
            throw new ElasticsearchStatusException("Found data frame transform persistent task [" + id + "] with incorrect params",
                RestStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void cancelDataFrameTask(String taskId, String dataFrameId, Exception exception, Consumer<Exception> onFailure) {
        persistentTasksService.sendRemoveRequest(taskId,
            new ActionListener<PersistentTasksCustomMetaData.PersistentTask<?>>() {
                @Override
                public void onResponse(PersistentTasksCustomMetaData.PersistentTask<?> task) {
                    // We succeeded in cancelling the persistent task, but the
                    // problem that caused us to cancel it is the overall result
                    onFailure.accept(exception);
                }

                @Override
                public void onFailure(Exception e) {
                    logger.error("[" + dataFrameId + "] Failed to cancel persistent task that could " +
                        "not be assigned due to [" + exception.getMessage() + "]", e);
                    onFailure.accept(exception);
                }
            }
        );
    }

    private void waitForDataFrameTaskStarted(String taskId,
                                             Transform params,
                                             TimeValue timeout,
                                             ActionListener<Boolean> listener) {
        DataFramePredicate predicate = new DataFramePredicate();
        persistentTasksService.waitForPersistentTaskCondition(taskId, predicate, timeout,
            new PersistentTasksService.WaitForPersistentTaskListener<Transform>() {
                @Override
                public void onResponse(PersistentTasksCustomMetaData.PersistentTask<Transform>
                                           persistentTask) {
                    if (predicate.exception != null) {
                        // We want to return to the caller without leaving an unassigned persistent task
                        cancelDataFrameTask(taskId, params.getId(), predicate.exception, listener::onFailure);
                    } else {
                        listener.onResponse(true);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }

                @Override
                public void onTimeout(TimeValue timeout) {
                    listener.onFailure(new ElasticsearchException("Starting dataframe ["
                        + params.getId() + "] timed out after [" + timeout + "]"));
                }
            });
    }

    /**
     * Important: the methods of this class must NOT throw exceptions.  If they did then the callers
     * of endpoints waiting for a condition tested by this predicate would never get a response.
     */
    private class DataFramePredicate implements Predicate<PersistentTasksCustomMetaData.PersistentTask<?>> {

        private volatile Exception exception;

        @Override
        public boolean test(PersistentTasksCustomMetaData.PersistentTask<?> persistentTask) {
            if (persistentTask == null) {
                return false;
            }
            PersistentTasksCustomMetaData.Assignment assignment = persistentTask.getAssignment();
            if (assignment != null &&
                assignment.equals(PersistentTasksCustomMetaData.INITIAL_ASSIGNMENT) == false &&
                assignment.isAssigned() == false) {
                // For some reason, the task is not assigned to a node, but is no longer in the `INITIAL_ASSIGNMENT` state
                // Consider this a failure.
                exception = new ElasticsearchStatusException("Could not start dataframe, allocation explanation [" +
                    assignment.getExplanation() + "]", RestStatus.TOO_MANY_REQUESTS);
                return true;
            }
            // We just want it assigned so we can tell it to start working
            return assignment != null && assignment.isAssigned() && isNotStopped(persistentTask);
        }

        // checking for `isNotStopped` as the state COULD be marked as failed for any number of reasons
        // But if it is in a failed state, _stats will show as much and give good reason to the user.
        // If it is not able to be assigned to a node all together, we should just close the task completely
        private boolean isNotStopped(PersistentTasksCustomMetaData.PersistentTask<?> task) {
            TransformState state = (TransformState)task.getState();
            return state != null && state.getTaskState().equals(TransformTaskState.STOPPED) == false;
        }
    }
}
