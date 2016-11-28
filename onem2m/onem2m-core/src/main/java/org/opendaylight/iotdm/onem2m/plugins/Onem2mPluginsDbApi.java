/*
 * Copyright (c) 2015, 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.iotdm.onem2m.plugins;

import org.opendaylight.iotdm.onem2m.core.Onem2m;
import org.opendaylight.iotdm.onem2m.core.database.Onem2mDb;
import org.opendaylight.iotdm.onem2m.core.database.transactionCore.ResourceTreeReader;
import org.opendaylight.iotdm.onem2m.core.database.transactionCore.ResourceTreeWriter;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.iotdm.onem2m.rev150105.onem2m.resource.tree.Onem2mResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by bjanosik on 9/21/16.
 */
public class Onem2mPluginsDbApi {

    private static final Logger LOG = LoggerFactory.getLogger(Onem2mPluginsDbApi.class);

    private ResourceTreeWriter twc;
    private ResourceTreeReader trc;
    private static Onem2mPluginsDbApi api;
    private final List<PluginDbClientData> plugins = Collections.synchronizedList(new LinkedList<>());

    /**
     * States of the registered DB API client
     */
    enum IotdmPLuginDbClientState {
        /**
         * Initial state
         */
        INIT,

        /**
         * DB API client has been started successfully
         */
        STARTED,

        /**
         * DB API client has been stopped
         */
        STOPPED,

        /**
         * DB API client is in error state
         */
        ERROR
    }

    protected class PluginDbClientData {
        private final IotdmPluginDbClient client;
        private IotdmPLuginDbClientState state;

        public PluginDbClientData(IotdmPluginDbClient client) {
            this.client = client;
        }

        public IotdmPluginDbClient getClient() {
            return client;
        }

        public IotdmPLuginDbClientState getState() {
            return state;
        }

        public void setState(IotdmPLuginDbClientState state) {
            this.state = state;
        }
    }

    public static Onem2mPluginsDbApi getInstance() {
        if (api == null) {
            api = new Onem2mPluginsDbApi();
        }
        return api;
    }

    private Onem2mPluginsDbApi() {
    }

    public boolean isApiReady() {
        return (null != trc && null != twc);
    }

    public void registerDbReaderAndWriter(ResourceTreeWriter twc, ResourceTreeReader trc) {
        this.trc = trc;
        this.twc = twc;

        for (PluginDbClientData dbClientData : plugins) {
            try {
                dbClientData.getClient().dbClientStart(this.twc, this.trc);
                dbClientData.setState(IotdmPLuginDbClientState.STARTED);
            } catch (Exception e) {
                dbClientData.setState(IotdmPLuginDbClientState.ERROR);
                LOG.error("Failed to start DB Client plugin: {}, error message: {}",
                          dbClientData.getClient().getPluginName(), e);
            }
        }
    }

    public void unregisterDbReaderAndWriter() {
        for (PluginDbClientData dbClientData : plugins) {
            dbClientData.getClient().dbClientStop();
            dbClientData.setState(IotdmPLuginDbClientState.STOPPED);
        }
        trc = null;
        twc = null;
    }

    public List<String> getCseList() {
        return Onem2mDb.getInstance().getCseList(this.trc);
    }

    public String findResourceIdUsingURI(String uri) {
        return Onem2mDb.getInstance().findResourceIdUsingURI(this.trc, Onem2m.translateUriToOnem2m(uri));
    }

    public String getHierarchicalNameForResource(Onem2mResource onem2mResource) {
        return Onem2mDb.getInstance().getHierarchicalNameForResource(this.trc, onem2mResource);
    }

    public List<String> getHierarchicalResourceList(String startResourceId, int limit) {
        return Onem2mDb.getInstance().getHierarchicalResourceList(this.trc, startResourceId, limit);
    }

    public Onem2mResource getResource(String resourceId) {
        return Onem2mDb.getInstance().getResource(this.trc, resourceId);
    }

    public Onem2mResource getResourceUsingURI(String targetURI) {
        return Onem2mDb.getInstance().getResourceUsingURI(this.trc, targetURI);
    }

    public boolean isLatestCI(Onem2mResource onem2mResource) {
        return Onem2mDb.getInstance().isLatestCI(this.trc, onem2mResource);
    }

    public boolean isResourceIdUnderTargetId(String targetResourceId, String onem2mResourceId) {
        return Onem2mDb.getInstance().isResourceIdUnderTargetId(this.trc, targetResourceId, onem2mResourceId);
    }

    public String findCseForTarget(String targetResourceId) {
        return Onem2mDb.getInstance().findCseForTarget(this.trc, targetResourceId);
    }

    private void handleRegistrationError(String format, String... args) throws IotdmPluginRegistrationException {
        Onem2mPluginManagerUtils.handleRegistrationError(LOG, format, args);
    }

    protected void registerDbClientPlugin(IotdmPluginDbClient plugin) throws IotdmPluginRegistrationException {
        for (PluginDbClientData data : this.plugins) {
            if (data.getClient().isPlugin(plugin)) {
                handleRegistrationError("Attempt to multiple registration of DB client plugin: {}",
                                        plugin.getPluginName());
            }
        }

        PluginDbClientData dbClientData = new PluginDbClientData(plugin);
        boolean ret = this.plugins.add(dbClientData);
        if (ret) {
            if (this.isApiReady()) {
                try {
                    plugin.dbClientStart(this.twc, this.trc);
                    dbClientData.setState(IotdmPLuginDbClientState.STARTED);
                } catch (Exception e) {
                    dbClientData.setState(IotdmPLuginDbClientState.ERROR);
                    handleRegistrationError("Failed to start DB Client plugin: {}, error message: {}",
                                            plugin.getPluginName(), e.toString());
                }
            }
        } else {
            handleRegistrationError("Failed to register DB client plugin: {}", plugin.getPluginName());
        }

        LOG.info("Registered DB client plugin: {}", plugin.getPluginName());
    }

    protected void unregisterDbClientPlugin(IotdmPluginDbClient plugin) {
        for (PluginDbClientData data : this.plugins) {
            if (data.getClient().isPlugin(plugin)) {
                plugin.dbClientStop();
                this.plugins.remove(data);
                LOG.info("Unregistered DB client plugin: {}", plugin.getPluginName());
            }
        }
    }

    protected List<PluginDbClientData> getPlugins() {
        return plugins;
    }
}
