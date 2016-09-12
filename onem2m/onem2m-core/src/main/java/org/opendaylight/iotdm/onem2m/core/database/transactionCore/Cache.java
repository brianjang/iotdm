/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.iotdm.onem2m.core.database.transactionCore;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.Weigher;
import org.opendaylight.iotdm.onem2m.core.Onem2m;
import org.opendaylight.iotdm.onem2m.core.database.Onem2mDb;
import org.opendaylight.iotdm.onem2m.core.database.dao.DaoResourceTreeReader;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.onem2m.cse.list.Onem2mCse;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.onem2m.cse.list.Onem2mCseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.onem2m.cse.list.Onem2mCseKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.onem2m.resource.tree.Onem2mResource;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.onem2m.resource.tree.Onem2mResourceKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.onem2m.resource.tree.onem2m.resource.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Created by gguliash on 4/28/16.
 * e-mail vinmesmiti@gmail.com; gguliash@cisco.com
 */
public class Cache implements WriteOnlyCache, ReadOnlyCache {
    private static final Logger LOG = LoggerFactory.getLogger(Cache.class);
    private static final int CSE_MAP_BYTE_LIMIT = 1000;
    private static final int RESOURCE_MAP_SIZE_LIMIT = 400000;
    private final DaoResourceTreeReader daoResourceTreeReader;
    private final com.google.common.cache.LoadingCache<Onem2mResourceKey, Onem2mResourceElem> onem2mResourceCache =
            CacheBuilder.<Onem2mResourceKey, Onem2mResourceElem>newBuilder().
                    maximumWeight(RESOURCE_MAP_SIZE_LIMIT).weigher(new Weigher<Onem2mResourceKey, Onem2mResourceElem>() {
                @Override
                public int weigh(Onem2mResourceKey onem2mResourceKey, Onem2mResourceElem el) {
                    return 1;
                }
            }).concurrencyLevel(10000).
                    build(new CacheLoader<Onem2mResourceKey, Onem2mResourceElem>() {
                        @Override
                        public Onem2mResourceElem load(Onem2mResourceKey onem2mResourceKey) throws Exception {
                            return daoResourceTreeReader.retrieveResourceById(onem2mResourceKey);
                        }
                    });
    private com.google.common.cache.LoadingCache<Onem2mCseKey, Onem2mCse> onem2mCseCache =
            CacheBuilder.<Onem2mCseKey, Onem2mCse>newBuilder().
                    maximumWeight(CSE_MAP_BYTE_LIMIT).weigher(new Weigher<Onem2mCseKey, Onem2mCse>() {
                @Override
                public int weigh(Onem2mCseKey onem2mCseKey, Onem2mCse onem2mCse) {
                    int ret = 0;
                    if (onem2mCse.getResourceId() != null)
                        ret += onem2mCse.getResourceId().length();
                    if (onem2mCse.getKey() != null && onem2mCse.getKey().getName() != null)
                        ret += onem2mCse.getKey().getName().length();
                    if (onem2mCse.getName() != null)
                        ret += onem2mCse.getName().length();
                    return ret;
                }
            }).concurrencyLevel(10000)
                    .build(new CacheLoader<Onem2mCseKey, Onem2mCse>() {
                        @Override
                        public Onem2mCse load(Onem2mCseKey onem2mCseKey) throws Exception {
                            return daoResourceTreeReader.retrieveCseByName(onem2mCseKey);
                        }
                    });

    /**
     * Constructs empty Cache container
     *
     * @param daoResourceTreeReader is used to retrieve elements not in cache
     */
    public Cache(DaoResourceTreeReader daoResourceTreeReader) {
        this.daoResourceTreeReader = daoResourceTreeReader;
    }


    @Override
    public boolean createCseByName(String name, String resourceId) {
        final Onem2mCseKey key = new Onem2mCseKey(name);

        Onem2mCse onem2mCse = new Onem2mCseBuilder()
                .setKey(key)
                .setName(name)
                .setResourceId(resourceId)
                .build();
        onem2mCseCache.put(key, onem2mCse);
        return true;
    }

    /**
     * Returns Cse element if exists.
     * If element is in cache loads from cache, if not retrieves from DB.
     *
     * @param key of the Cse element.
     * @return returns null if Cse element with key does not exist, or DB threw error(Writes log.error).
     */
    @Override
    public Onem2mCse retrieveCseByName(Onem2mCseKey key) {
        try {
            return onem2mCseCache.get(key);
        } catch (ExecutionException e) {
            LOG.error("retrieveCseByName {}", e.getMessage());
            return null;
        } catch (CacheLoader.InvalidCacheLoadException e) {
            return null;
        }
    }

    @Override
    public Onem2mResource createResource(String resourceId, String resourceName, String jsonContent,
                                         String parentResourceId, String resourceType) {
        /**
         * Initialize empty oldestlatest lists for contentInstances, and subscriptions as these are the two sets
         * of lists that we need quick access to when processing requests for container/latest and notifications
         * These act like the head/tail of a linked list where the key of the list is the resource type
         */
        List<OldestLatest> oldestLatestList = new ArrayList<OldestLatest>();

        OldestLatest oldestLatestContentInstance = new OldestLatestBuilder()
                .setKey(new OldestLatestKey(Onem2m.ResourceType.CONTENT_INSTANCE))
                .setResourceType(Onem2m.ResourceType.CONTENT_INSTANCE)
                .setLatestId(Onem2mDb.NULL_RESOURCE_ID)
                .setOldestId(Onem2mDb.NULL_RESOURCE_ID)
                .build();
        oldestLatestList.add(oldestLatestContentInstance);

        OldestLatest oldestLatestSubscription = new OldestLatestBuilder()
                .setKey(new OldestLatestKey(Onem2m.ResourceType.SUBSCRIPTION))
                .setResourceType(Onem2m.ResourceType.SUBSCRIPTION)
                .setLatestId(Onem2mDb.NULL_RESOURCE_ID)
                .setOldestId(Onem2mDb.NULL_RESOURCE_ID)
                .build();
        oldestLatestList.add(oldestLatestSubscription);

        /**
         * Initialize the resource
         */
        Onem2mResourceKey key = new Onem2mResourceKey(resourceId);

        Onem2mResourceElem cacheElem = new Onem2mResourceElem(daoResourceTreeReader, resourceId, parentResourceId,
                resourceName, resourceType, oldestLatestList,
                Collections.<Child>emptyList(), jsonContent);

        onem2mResourceCache.put(key, cacheElem);

        return cacheElem;
    }

    @Override
    public void updateResourceOldestLatestInfo(String resourceId,
                                               String resourceType,
                                               String oldest,
                                               String latest) {
        Onem2mResourceKey key = new Onem2mResourceKey(resourceId);
        Onem2mResourceElem last = onem2mResourceCache.getIfPresent(key);

        if (last == null) return; // It's not in cache so will not update cache

        OldestLatest oldestlatest = new OldestLatestBuilder()
                .setKey(new OldestLatestKey(resourceType))
                .setResourceType(resourceType)
                .setLatestId(latest)
                .setOldestId(oldest)
                .build();

        OldestLatestKey oldestKey = oldestlatest.getKey();
        last.addOldestLatest(oldestKey, oldestlatest);
    }

    @Override
    public OldestLatest retrieveOldestLatestByResourceType(Onem2mResourceKey key, OldestLatestKey oldestLatestKey) {
        Onem2mResourceElem head = retrieveResourceById(key);
        if (head != null)
            return head.getOldestLatest(oldestLatestKey);
        else
            return null;
    }

    @Override
    public Onem2mResourceElem retrieveResourceById(Onem2mResourceKey key) {
        try {
            return onem2mResourceCache.get(key);
        } catch (ExecutionException e) {
            LOG.error("retrieveResourceById: Had problem while retrieving Resource resourceId = {}", key.getResourceId());
            return null;
        } catch (CacheLoader.InvalidCacheLoadException e) {
            return null;
        }
    }

//    /**
//     * Retrieve the attr by name from the data store
//     * @param resourceId resource id of the attr
//     * @param attrName name of attr
//     * @return Attr
//     */
//    public Attr retrieveAttrByName(String resourceId, String attrName) {
//
//        InstanceIdentifier<Attr> iid = InstanceIdentifier.create(Onem2mResourceTree.class)
//                .child(Onem2mResource.class, new Onem2mResourceKey(resourceId))
//                .child(Attr.class, new AttrKey(attrName));
//
//        return DbTransaction.retrieve(dataBroker, iid, LogicalDatastoreType.OPERATIONAL);
//    }
//
//    /**
//     * Delete the attr by name from the data store
//     * @param dbTxn transaction id
//     * @param resourceId this resource
//     * @param attrName name
//     */
//    public void deleteAttr(DbTransaction dbTxn, String resourceId, String attrName) {
//
//        InstanceIdentifier<Attr> iid = InstanceIdentifier.create(Onem2mResourceTree.class)
//                .child(Onem2mResource.class, new Onem2mResourceKey(resourceId))
//                .child(Attr.class, new AttrKey(attrName));
//
//        dbTxn.delete(iid, LogicalDatastoreType.OPERATIONAL);
//    }
//

    @Override
    public void updateJsonResourceContentString(String resourceId, String jsonResourceContent) {
        Onem2mResourceKey key = new Onem2mResourceKey(resourceId);

        Onem2mResourceElem head = onem2mResourceCache.getIfPresent(key);
        if (head == null) return;

        head.setResourceContentJsonString(jsonResourceContent);

    }

//    /**
//     *
//     * @param dbTxn transaction id
//     * @param resourceId this resource
//     * @param attrSet updated memberList
//     */
//    public void updateAttrSet(DbTransaction dbTxn, String resourceId, AttrSet attrSet) {
//
//        InstanceIdentifier<AttrSet> iid = InstanceIdentifier.create(Onem2mResourceTree.class)
//                .child(Onem2mResource.class, new Onem2mResourceKey(resourceId))
//                .child(AttrSet.class, attrSet.getKey());
//
//        dbTxn.create(iid, attrSet, LogicalDatastoreType.OPERATIONAL);
//    }
//
//    /**
//     * Delete the attr by name from the data store
//     * @param dbTxn transaction id
//     * @param resourceId this resource
//     * @param attrSetName name
//     */
//    public void deleteAttrSet(DbTransaction dbTxn, String resourceId, String attrSetName) {
//
//        InstanceIdentifier<AttrSet> iid = InstanceIdentifier.create(Onem2mResourceTree.class)
//                .child(Onem2mResource.class, new Onem2mResourceKey(resourceId))
//                .child(AttrSet.class, new AttrSetKey(attrSetName));
//
//        dbTxn.delete(iid, LogicalDatastoreType.OPERATIONAL);
//    }


    @Override
    public Child retrieveChildByName(Onem2mResourceKey key, ChildKey childKey) {
        Onem2mResourceElem head = retrieveResourceById(key);
        if (head == null) return null;

        return head.getChild(childKey);
    }

    @Override
    public void createParentChildLink(String parentResourceId,
                                      String childName, String childResourceId,
                                      String prevId, String nextId) {
        Onem2mResourceKey key = new Onem2mResourceKey(parentResourceId);
        Onem2mResourceElem head = onem2mResourceCache.getIfPresent(key);
        if (head == null) return;

        Child child = new ChildBuilder()
                .setKey(new ChildKey(childName))
                .setName(childName)
                .setResourceId(childResourceId)
                .setNextId(nextId)
                .setPrevId(prevId)
                .build();

        head.putIfAbsentChild(child.getKey(), child);

    }

    @Override
    public void updateChildSiblingNextInfo(String parentResourceId,
                                           Child child,
                                           String nextId) {
        Onem2mResourceKey key = new Onem2mResourceKey(parentResourceId);
        Onem2mResourceElem head = onem2mResourceCache.getIfPresent(key);
        if (head == null) return;

        Child updateChild = new ChildBuilder(child)
                .setNextId(nextId)
                .build();

        head.addChild(updateChild.getKey(), updateChild);
    }

    @Override
    public void updateChildSiblingPrevInfo(String parentResourceId,
                                           Child child,
                                           String prevId) {
        Onem2mResourceKey key = new Onem2mResourceKey(parentResourceId);
        Onem2mResourceElem head = onem2mResourceCache.getIfPresent(key);
        if (head == null) return;

        Child updateChild = new ChildBuilder(child)
                .setPrevId(prevId)
                .build();

        head.addChild(updateChild.getKey(), updateChild);

    }

    @Override
    public void removeParentChildLink(String parentResourceId, String childResourceName) {
        Onem2mResourceKey key = new Onem2mResourceKey(parentResourceId);
        Onem2mResourceElem head = onem2mResourceCache.getIfPresent(key);
        if (head == null) return;

        ChildKey childKey = new ChildKey(childResourceName);
        head.removeChild(childKey);

    }

    @Override
    public Onem2mResource retrieveChildResourceByName(Onem2mResourceKey key, ChildKey childKey) {
        String id = retrieveChildResourceIDByName(key, childKey);
        if (id != null) {
            Onem2mResource ret = retrieveResourceById(new Onem2mResourceKey(id));
            return ret;
        }

        return null;

    }

    @Override
    public String retrieveChildResourceIDByName(Onem2mResourceKey key, ChildKey childKey) {
        Onem2mResourceElem head = retrieveResourceById(key);
        if (head != null) {
            Child child = head.getChild(childKey);
            if (child != null) return child.getResourceId();
            else {
                return null;
            }
        } else {
            LOG.error("retrieveChildResourceIDByName: Tried to retrieveChildResourceByName of parentResourceId  = {} but did not exist", key.getResourceId());
            return null;
        }
    }

    @Override
    public void deleteResourceById(String resourceId) {
        Onem2mResourceKey key = new Onem2mResourceKey(resourceId);
        onem2mResourceCache.invalidate(key);
    }

    /**
     * Cleanup the data store.
     */
    @Override
    public void reInitializeDatastore() {
        onem2mResourceCache.invalidateAll();
        onem2mCseCache.invalidateAll();
    }

}