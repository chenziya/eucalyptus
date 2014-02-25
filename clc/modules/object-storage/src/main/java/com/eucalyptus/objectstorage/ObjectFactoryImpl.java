/*************************************************************************
* Copyright 2009-2014 Eucalyptus Systems, Inc.
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation; version 3 of the License.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see http://www.gnu.org/licenses/.
*
* Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
* CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
* additional information or have any questions.
************************************************************************/

package com.eucalyptus.objectstorage;

import java.io.InputStream;
import java.nio.channels.Channel;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.eucalyptus.auth.Accounts;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.principal.User;
import com.eucalyptus.entities.Transactions;
import com.eucalyptus.objectstorage.entities.ObjectEntity;
import com.eucalyptus.objectstorage.entities.ObjectStorageGlobalConfiguration;
import com.eucalyptus.objectstorage.entities.PartEntity;
import com.eucalyptus.objectstorage.exceptions.s3.AccountProblemException;
import com.eucalyptus.objectstorage.exceptions.s3.InternalErrorException;
import com.eucalyptus.objectstorage.exceptions.s3.NoSuchBucketException;
import com.eucalyptus.objectstorage.exceptions.s3.S3Exception;
import com.eucalyptus.objectstorage.metadata.ObjectMetadataManager;
import com.eucalyptus.objectstorage.msgs.CompleteMultipartUploadResponseType;
import com.eucalyptus.objectstorage.msgs.CompleteMultipartUploadType;
import com.eucalyptus.objectstorage.msgs.DeleteObjectResponseType;
import com.eucalyptus.objectstorage.msgs.DeleteObjectType;
import com.eucalyptus.objectstorage.msgs.InitiateMultipartUploadResponseType;
import com.eucalyptus.objectstorage.msgs.InitiateMultipartUploadType;
import com.eucalyptus.objectstorage.msgs.ObjectStorageDataResponseType;
import com.eucalyptus.objectstorage.msgs.PutObjectResponseType;
import com.eucalyptus.objectstorage.msgs.PutObjectType;
import com.eucalyptus.objectstorage.msgs.UploadPartResponseType;
import com.eucalyptus.objectstorage.msgs.UploadPartType;
import com.eucalyptus.objectstorage.providers.ObjectStorageProviderClient;
import com.eucalyptus.objectstorage.util.AclUtils;
import com.eucalyptus.objectstorage.util.ObjectStorageProperties;
import com.eucalyptus.storage.msgs.s3.AccessControlPolicy;
import com.eucalyptus.storage.msgs.s3.Part;
import com.eucalyptus.util.EucalyptusCloudException;
import org.apache.log4j.Logger;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ObjectFactoryImpl implements ObjectFactory {
    private static final Logger LOG = Logger.getLogger(ObjectFactoryImpl.class);

    /*
     * The thread pool to handle the PUT operations to the backend.
      * Use another thread to allow status updates on the object entity
      * in the db to renew the lease to prevent OSG object GC from occuring while
      * the object is still uploading.
     */
    private static final int CORE_POOL_SIZE = 10;
    private static final int MAX_POOL_SIZE = 100;
    private static final int MAX_QUEUE_SIZE = 2 * MAX_POOL_SIZE;
    private static final ExecutorService PUT_OBJECT_SERVICE = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(MAX_QUEUE_SIZE));

	@Override
	public ObjectEntity createObject(@Nonnull final ObjectStorageProviderClient provider,
                                     @Nonnull ObjectEntity entity,
                                     @Nonnull final InputStream content,
                                     @Nonnull final User requestUser) throws S3Exception {

        final ObjectMetadataManager objectManager = ObjectMetadataManagers.getInstance();

        //Initialize metadata for the object
        if(BucketState.extant.equals(entity.getBucket().getState())) {
            //Initialize the object metadata.
            try {
                entity = objectManager.initiateCreation(entity);
            } catch(Exception e) {
                LOG.warn("Error initiating an object in the db:", e);
                throw new InternalErrorException(entity.getResourceFullName());
            }
        } else {
            throw new NoSuchBucketException(entity.getBucket().getBucketName());
        }

        final Date lastModified;
        final String etag;
        PutObjectResponseType response;

        try {
            final ObjectEntity uploadingObject = entity;
            final PutObjectType putRequest = new PutObjectType();
            putRequest.setBucket(uploadingObject.getBucket().getBucketUuid());
            putRequest.setKey(uploadingObject.getObjectUuid());
            putRequest.setUser(requestUser);
            putRequest.setContentLength(entity.getSize().toString());

            Callable<PutObjectResponseType> putCallable = new Callable<PutObjectResponseType>() {

                @Override
                public PutObjectResponseType call() throws Exception {
                    LOG.debug("Putting data");
                    PutObjectResponseType response = provider.putObject(putRequest, content);
                    LOG.debug("Done with put. " + response.getStatusMessage());
                    return response;
                }
            };

            //Send the data
            final FutureTask<PutObjectResponseType> putTask = new FutureTask<>(putCallable);
            PUT_OBJECT_SERVICE.execute(putTask);
            final long failTime = System.currentTimeMillis() + (ObjectStorageGlobalConfiguration.failed_put_timeout_hrs * 60 * 60 * 1000);
            final long checkIntervalSec = ObjectStorageProperties.OBJECT_CREATION_EXPIRATION_INTERVAL_SEC / 2;
            final AtomicReference<ObjectEntity> entityRef = new AtomicReference<>(uploadingObject);
            Callable updateTimeout = new Callable() {
                @Override
                public Object call() throws Exception {
                    ObjectEntity tmp = entityRef.get();
                    try {
                        entityRef.getAndSet(ObjectMetadataManagers.getInstance().updateCreationTimeout(tmp));
                    } catch(Exception ex) {
                        LOG.warn("Could not update the creation expiration time for ObjectUUID " + tmp.getObjectUuid() + " Will retry next interval", ex);
                    }
                    return entityRef.get();
                }
            };
            response = waitForCompletion(putTask, uploadingObject.getObjectUuid(), updateTimeout, failTime, checkIntervalSec);
            lastModified = response.getLastModified();
            etag = response.getEtag();

        } catch(Exception e) {
            LOG.error("Data PUT failure to backend for bucketuuid / objectuuid : " + entity.getBucket().getBucketUuid() + "/" + entity.getObjectUuid(),e);

            //Remove metadata and return failure
            try {
                ObjectMetadataManagers.getInstance().transitionObjectToState(entity, ObjectState.deleting);
            } catch(Exception ex) {
                LOG.warn("Failed to mark failed object entity in deleting state on failure rollback. Will be cleaned later.", e);
            }

            //ObjectMetadataManagers.getInstance().delete(objectEntity);
            throw new InternalErrorException(entity.getObjectKey());
        }

        try {
            //fireRepairTask(bucket, savedEntity.getObjectKey());
            //Update metadata to "extant". Retry as necessary
            return ObjectMetadataManagers.getInstance().finalizeCreation(entity, lastModified, etag);
        } catch(Exception e) {
            LOG.warn("Failed to update object metadata for finalization. Failing PUT operation", e);
            throw new InternalErrorException(entity.getResourceFullName());
        }
    }

    /**
     * Wait for the upload task to complete to the backend. Update
     * the creationExpiration time on intervals to ensure that other OSGs
     * don't mistake the upload as failed.
     *
     * @return
     * @throws Exception
     */
    private <T extends ObjectStorageDataResponseType> T waitForCompletion(@Nonnull Future<T> pendingTask, String objectUuid, @Nonnull Callable timeoutUpdate, final long failOperationTimeSec, final long checkIntervalSec) throws Exception {
        T response;
        //Final time to wait before declaring failure.

        while(System.currentTimeMillis() < failOperationTimeSec * 1000) {
            try {
                response = pendingTask.get(checkIntervalSec, TimeUnit.SECONDS);
                return response;
            } catch(TimeoutException e) {
                //fall thru and retry
                timeoutUpdate.call();

                /*try {
                    entity = ObjectMetadataManagers.getInstance().updateCreationTimeout(entity);
                } catch(Exception ex) {
                    LOG.warn("Could not update the creation expiration time for ObjectUUID " + entity.getObjectUuid() + " Will retry next interval", e);
                }*/
            } catch(CancellationException e) {
                LOG.debug("PUT operation cancelled for object/part UUID " + objectUuid);
                throw e;
            } catch(ExecutionException e) {
                LOG.debug("PUT operation failed due to exception. object/part UUID " + objectUuid, e);
                throw e;
            } catch(InterruptedException e) {
                LOG.warn("PUT operation interrupted. Object/Part UUID " + objectUuid, e);
                throw e;
            }
        }

        //Big fail. This should not happen. Means the upload lasted 24hrs or more
        throw new Exception("Timed out on upload");
    }

    @Override
    public void logicallyDeleteVersion(@Nonnull ObjectEntity entity, @Nonnull User requestUser) throws S3Exception {
        if(entity.getBucket() == null) {
            throw new InternalErrorException();
        }

        if(!entity.getIsDeleteMarker()) {
            ObjectMetadataManagers.getInstance().transitionObjectToState(entity, ObjectState.deleting);
        } else {
            //Delete the delete marker.
            ObjectMetadataManagers.getInstance().delete(entity);
        }
    }

    @Override
    public void logicallyDeleteObject(@Nonnull ObjectEntity entity, @Nonnull User requestUser) throws S3Exception {
        if(entity.getBucket() == null) {
            throw new InternalErrorException();
        }

        switch(entity.getBucket().getVersioning()) {
            case Suspended:
            case Enabled:
                if(!entity.getIsDeleteMarker()) {
                    try {
                        //Create a "private" acp for the delete marker
                        AccessControlPolicy acp = AclUtils.processNewResourcePolicy(requestUser, null, entity.getBucket().getOwnerCanonicalId());
                        //Create new deleteMarker
                        ObjectMetadataManagers.getInstance().generateAndPersistDeleteMarker(entity, acp, requestUser);
                    } catch(Exception e) {
                        LOG.warn("Failure configuring and persisting the delete marker for object " + entity.getResourceFullName());
                        throw new InternalErrorException(e);
                    }
                } else {
                    //Do nothing, already a delete marker found.
                }
                break;
            case Disabled:
                //Mark the object itself for deletion.
                ObjectMetadataManagers.getInstance().transitionObjectToState(entity, ObjectState.deleting);
                break;
        }
    }

    @Override
	public void actuallyDeleteObject(@Nonnull ObjectStorageProviderClient provider, @Nonnull ObjectEntity entity, @Nullable User requestUser) throws S3Exception {

        try {
            entity = ObjectMetadataManagers.getInstance().transitionObjectToState(entity, ObjectState.deleting);
        } catch(Exception e) {
            LOG.debug("Could not mark metadata for deletion", e);
            throw e;
        }

        if(entity.getIsDeleteMarker()) {
            //Delete markers are just removed, no backend call needed.
            ObjectMetadataManagers.getInstance().delete(entity);
            return;
        }

        //Issue delete to backend
        DeleteObjectType deleteRequest;
        DeleteObjectResponseType deleteResponse;
        LOG.trace("Deleting object " + entity.getObjectUuid() + ".");
        deleteRequest = new DeleteObjectType();

        //Always use the system admin for deletions if not given an explicit user
        if(requestUser == null) {
            try {
                requestUser = Accounts.lookupSystemAdmin();
            } catch(AuthException e) {
                LOG.trace("System admin account not found for object deletion. Cannot remove object with uuid " + entity.getObjectUuid());
                throw new AccountProblemException("Eucalyptus/Admin");
            }
        }

        try {
            deleteRequest.setUser(requestUser);
            deleteRequest.setBucket(entity.getBucket().getBucketUuid());
            deleteRequest.setKey(entity.getObjectUuid());

            try {
                deleteResponse = provider.deleteObject(deleteRequest);
                if(!(HttpResponseStatus.NO_CONTENT.equals(deleteResponse.getStatus()) || HttpResponseStatus.OK.equals(deleteResponse.getStatus()))) {
                    LOG.trace("Backend did not confirm deletion of " + deleteRequest.getBucket() + "/" + deleteRequest.getKey() + " via request: " + deleteRequest.toString());
                    throw new Exception("Object could not be confirmed as deleted.");
                }
            } catch(S3Exception e) {
                if(HttpResponseStatus.NOT_FOUND.equals(e.getStatus())) {
                    //Ok, fall through.
                } else {
                    throw e;
                }
            }

            //Object does not exist on backend, remove record
            Transactions.delete(entity);

        } catch(EucalyptusCloudException ex) {
            //Failed. Keep record so we can retry later
            LOG.trace("Error in response from backend on deletion request for object on backend: " + deleteRequest.getBucket() + "/" + deleteRequest.getKey());
        } catch(Exception e) {
            LOG.warn("Error deleting object on backend. Will retry later", e);
        }
	}

    /**
     * Create a multipart Upload (get an Id from the backend and initialize the metadata.
     * Returns a persisted uploadId record as an ObjectEntity with the uploadId in state 'mpu-pending'
     * @param provider
     * @param upload
     * @param requestUser
     * @return
     * @throws S3Exception
     */
    @Override
    public ObjectEntity createMultipartUpload(final ObjectStorageProviderClient provider, ObjectEntity upload, User requestUser) throws S3Exception {
        final ObjectMetadataManager objectManager = ObjectMetadataManagers.getInstance();

        //Initialize metadata for the object
        if(BucketState.extant.equals(upload.getBucket().getState())) {
            //Initialize the object metadata.
            try {
                upload = objectManager.initiateCreation(upload);
            } catch(Exception e) {
                LOG.warn("Error initiating an object in the db:", e);
                throw new InternalErrorException(upload.getResourceFullName());
            }
        } else {
            throw new NoSuchBucketException(upload.getBucket().getBucketName());
        }

        try {
            final InitiateMultipartUploadType initRequest = new InitiateMultipartUploadType();
            initRequest.setBucket(upload.getBucket().getBucketUuid());
            initRequest.setKey(upload.getObjectUuid());
            initRequest.setUser(requestUser);
            initRequest.setStorageClass(upload.getStorageClass());
            initRequest.setAccessControlList(upload.getAccessControlPolicy().getAccessControlList());

            LOG.trace("Initiating MPU on backend");
            InitiateMultipartUploadResponseType response = provider.initiateMultipartUpload(initRequest);
            upload.setObjectModifiedTimestamp(response.getLastModified());
            upload.setUploadId(response.getUploadId());
            LOG.trace("Done with MPU init on backend. " + response.getStatusMessage());
        } catch(Exception e) {
            LOG.error("InitiateMPU failure to backend for bucketuuid / objectuuid : " + upload.getBucket().getBucketUuid() + "/" + upload.getObjectUuid(),e);

            //Remove metadata and return failure
            try {
                ObjectMetadataManagers.getInstance().transitionObjectToState(upload, ObjectState.deleting);
            } catch(Exception ex) {
                LOG.warn("Failed to mark failed object entity in deleting state on failure rollback. Will be cleaned later.", e);
            }

            throw new InternalErrorException(upload.getObjectKey());
        }

        try {
            //Update metadata to "mpu-pending". Retry as necessary. Just used the entity itself for holding the timestamp and uploadId. Those
            // will be set and persisted in this call.
            return ObjectMetadataManagers.getInstance().finalizeMultipartInit(upload, upload.getObjectModifiedTimestamp(), upload.getUploadId());
        } catch(Exception e) {
            LOG.warn("Failed to update object metadata for finalization. Failing InitiateMPU operation", e);
            throw new InternalErrorException(upload.getResourceFullName());
        }
    }

    /**
     * Create the named object part in metadata and on the backend.
     * @return the ObjectEntity object representing the successfully created object
     */
    @Override
    public PartEntity createObjectPart(final ObjectStorageProviderClient provider, ObjectEntity mpuEntity, PartEntity entity, final InputStream content, User requestUser) throws S3Exception {
        //Initialize metadata for the object
        if(BucketState.extant.equals(entity.getBucket().getState())) {
            //Initialize the object metadata.
            try {
                entity = MpuPartMetadataManagers.getInstance().initiatePartCreation(entity);
            } catch(Exception e) {
                //Metadata failure
                LOG.error("Error initializing metadata for object creation: " + entity.getResourceFullName());
                InternalErrorException ex = new InternalErrorException(entity.getResourceFullName());
                ex.initCause(e);
                throw ex;
            }

        } else {
            throw new NoSuchBucketException(entity.getBucket().getBucketName());
        }

        final Date lastModified;
        final String etag;
        UploadPartResponseType response;

        try {
            final PartEntity uploadingObject = entity;
            final UploadPartType putRequest = new UploadPartType();
            putRequest.setBucket(uploadingObject.getBucket().getBucketUuid());
            putRequest.setKey(mpuEntity.getObjectUuid());
            putRequest.setUser(requestUser);
            putRequest.setContentLength(entity.getSize().toString());
            putRequest.setPartNumber(String.valueOf(entity.getPartNumber()));
            putRequest.setUploadId(entity.getUploadId());

            Callable<UploadPartResponseType> putCallable = new Callable<UploadPartResponseType>() {

                @Override
                public UploadPartResponseType call() throws Exception {
                    LOG.debug("Putting data");
                    UploadPartResponseType response = provider.uploadPart(putRequest, content);
                    LOG.debug("Done with put. " + response.getStatusMessage());
                    return response;
                }
            };

            //Send the data
            final FutureTask<UploadPartResponseType> putTask = new FutureTask<>(putCallable);
            PUT_OBJECT_SERVICE.execute(putTask);
            final long failTime = System.currentTimeMillis() + (ObjectStorageGlobalConfiguration.failed_put_timeout_hrs * 60 * 60 * 1000);
            final long checkIntervalSec = ObjectStorageProperties.OBJECT_CREATION_EXPIRATION_INTERVAL_SEC / 2;
            final AtomicReference<PartEntity> entityRef = new AtomicReference<>(uploadingObject);
            Callable updateTimeout = new Callable() {
                @Override
                public Object call() throws Exception {
                    PartEntity tmp = entityRef.get();
                    try {
                        entityRef.getAndSet(MpuPartMetadataManagers.getInstance().updateCreationTimeout(tmp));
                    } catch(Exception ex) {
                        LOG.warn("Could not update the creation expiration time for PartUUID " + tmp.getPartUuid() + " Will retry next interval", ex);
                    }
                    return entityRef.get();
                }
            };

            response = waitForCompletion(putTask, uploadingObject.getPartUuid(), updateTimeout, failTime, checkIntervalSec);
            lastModified = response.getLastModified();
            etag = response.getEtag();

        } catch(Exception e) {
            LOG.error("Data PUT failure to backend for bucketuuid / objectuuid : " + entity.getBucket().getBucketUuid() + "/" + entity.getPartUuid(),e);

            //Remove metadata and return failure
            try {
                MpuPartMetadataManagers.getInstance().transitionPartToState(entity, ObjectState.deleting);
            } catch(Exception ex) {
                LOG.error("Failed to mark failed object entity in deleting state on failure rollback. Will be cleaned later.", e);
            }

            //ObjectMetadataManagers.getInstance().delete(objectEntity);
            throw new InternalErrorException(entity.getObjectKey());
        }

        //fireRepairTask(bucket, savedEntity.getObjectKey());
        try {
            //Update metadata to "extant". Retry as necessary
            return MpuPartMetadataManagers.getInstance().finalizeCreation(entity, lastModified, etag);
        } catch(Exception e) {

            //Return failure to user, let normal cleanup handle this case since we can't update the metadata
            LOG.error("Failed to update object metadata for finalization. Failing PUT operation", e);
            throw new InternalErrorException(entity.getResourceFullName());
        }
    }

    /**
     * Commits a Multipart Upload into an extant object entity.
     * @param provider
     * @param mpuEntity the ObjectEntity that is the upload parent record, as supplied by the ObjectMetadataManager.lookupUpload()
     * @param requestUser
     * @return
     * @throws S3Exception
     */
    @Override
    public ObjectEntity completeMultipartUpload(ObjectStorageProviderClient provider, ObjectEntity mpuEntity, ArrayList<Part> partList, User requestUser) throws S3Exception {
        try {
            CompleteMultipartUploadType commitRequest = new CompleteMultipartUploadType();
            commitRequest.setParts(partList);
            commitRequest.setBucket(mpuEntity.getBucket().getBucketUuid());
            commitRequest.setKey(mpuEntity.getObjectUuid());
            commitRequest.setUploadId(mpuEntity.getUploadId());

            // TODO: this is broken, need the exact set of parts used, not all parts
            long fullSize = MpuPartMetadataManagers.getInstance().processPartListAndGetSize(partList, MpuPartMetadataManagers.getInstance().getParts(mpuEntity.getBucket(), mpuEntity.getObjectKey(), mpuEntity.getUploadId()));
            mpuEntity.setSize(fullSize);

            try {
                CompleteMultipartUploadResponseType response = provider.completeMultipartUpload(commitRequest);
                mpuEntity.seteTag(response.getEtag());
            } catch(EucalyptusCloudException e) {
                LOG.warn("Error from backend on CompleteMultipartUpload ",e);
                throw e;
            }

            ObjectEntity completedEntity = ObjectMetadataManagers.getInstance().finalizeCreation(mpuEntity, new Date(), mpuEntity.geteTag());

            //all okay, delete all parts
            try {
                MpuPartMetadataManagers.getInstance().removeParts(completedEntity.getUploadId());
            } catch (Exception e) {
                throw new InternalErrorException("Could not remove parts for: " + mpuEntity.getUploadId());
            }
            return completedEntity;
        } catch(S3Exception e) {
            throw e;
        } catch(Exception e) {
            LOG.warn("Failed commit of multipart upload " + mpuEntity.getUploadId(), e);
            InternalErrorException ex = new InternalErrorException(mpuEntity.getUploadId());
            ex.initCause(e);
            throw ex;
        }
    }

    /**
     * Flushes the mulitpart upload and all artifacts that are not committed.
     * @param entity ObjectEntity record for object to delete
     */
    @Override
    public void flushMultipartUpload(ObjectStorageProviderClient provider, ObjectEntity entity, User requestUser) throws S3Exception {
        try {
            MpuPartMetadataManagers.getInstance().removeParts(entity.getUploadId());
        } catch(Exception e) {
            LOG.warn("Error removing non-committed parts",e);
            InternalErrorException ex = new InternalErrorException();
            ex.initCause(e);
            throw ex;
        }

    }

}
