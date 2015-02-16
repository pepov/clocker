/*
 * Copyright 2014-2015 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.networking.sdn;

import java.net.InetAddress;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.render.RendererHints;
import brooklyn.entity.basic.DelegateEntity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.entity.container.docker.DockerHost;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.feed.ConfigToAttributes;
import brooklyn.networking.VirtualNetwork;
import brooklyn.util.net.Cidr;

import com.google.common.collect.Multimap;

/**
 * An SDN agent process on a Docker host.
 */
public abstract class SdnAgentImpl extends SoftwareProcessImpl implements SdnAgent {

    private static final Logger LOG = LoggerFactory.getLogger(SdnAgent.class);

    protected transient final Object addressMutex = new Object[0];

    @Override
    public void init() {
        super.init();

        ConfigToAttributes.apply(this, DOCKER_HOST);
        ConfigToAttributes.apply(this, SDN_PROVIDER);
    }

    @Override
    public SdnAgentDriver getDriver() {
        return (SdnAgentDriver) super.getDriver();
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();
        super.connectServiceUpIsRunning();
    }

    @Override
    public void disconnectSensors() {
        super.disconnectServiceUpIsRunning();
        super.disconnectSensors();
    }

    @Override
    public DockerHost getDockerHost() {
        return getAttribute(DOCKER_HOST);
    }

    @Override
    public void preStart() {
        synchronized (addressMutex) {
            InetAddress address = getAttribute(SDN_PROVIDER).getNextAgentAddress(getId());
            setAttribute(SDN_AGENT_ADDRESS, address);
        }
    }

    @Override
    public void postStart() {
        Entities.deproxy(getDockerHost()).setAttribute(SDN_AGENT, this);
    }

    @Override
    public void rebind() {
        super.rebind();
        // TODO implement custom SDN agent rebind logic
    }

    @Override
    public InetAddress attachNetwork(String containerId, String networkId, String networkName) {
        synchronized (addressMutex) {
            Map<String, Cidr> networks = getAttribute(SDN_PROVIDER).getAttribute(SdnProvider.SUBNETS);
            if (!networks.containsKey(networkId)) {
                // Get a CIDR for the subnet from the availabkle pool and create a virtual network
                Cidr subnetCidr = getAttribute(SdnAgent.SDN_PROVIDER).getNextSubnetCidr();
                EntitySpec<VirtualNetwork> networkSpec = EntitySpec.create(VirtualNetwork.class)
                        .configure(VirtualNetwork.NETWORK_ID, networkId)
                        .configure(VirtualNetwork.NETWORK_NAME, networkName)
                        .configure(VirtualNetwork.NETWORK_CIDR, subnetCidr);

                // Start and then add this virtual network as a child of SDN_NETWORKS
                VirtualNetwork network = getAttribute(SDN_PROVIDER).getAttribute(SdnProvider.SDN_NETWORKS).addChild(networkSpec);
                Entities.manage(network);
                Entities.waitForServiceUp(network);
            }

            InetAddress address = getDriver().attachNetwork(containerId, networkId);
            LOG.info("Attached container ID {} to {}: {}", new Object[] { containerId, networkId,  address.getHostAddress() });

            Multimap<String, InetAddress> addresses = getAttribute(SDN_PROVIDER).getAttribute(SdnProvider.CONTAINER_ADDRESSES);
            addresses.put(containerId, address);
            Entities.deproxy(getAttribute(SDN_PROVIDER)).setAttribute(SdnProvider.CONTAINER_ADDRESSES, addresses);

            return address;
        }
    }

    @Override
    public String provisionNetwork(VirtualNetwork network) {
        String networkId = network.getAttribute(VirtualNetwork.NETWORK_ID);
        String networkName = network.getAttribute(VirtualNetwork.NETWORK_NAME);

        // Record the network CIDR being provisioned, allocating if required
        Cidr subnetCidr = network.getConfig(VirtualNetwork.NETWORK_CIDR);
        if (subnetCidr == null) {
            subnetCidr = getAttribute(SDN_PROVIDER).getNextSubnetCidr();
        }
        Entities.deproxy(network).setAttribute(VirtualNetwork.NETWORK_CIDR, subnetCidr);
        Map<String, Cidr> subnets = getAttribute(SDN_PROVIDER).getAttribute(SdnProvider.SUBNETS);
        subnets.put(networkId, subnetCidr);
        Entities.deproxy(getAttribute(SDN_PROVIDER)).setAttribute(SdnProvider.SUBNETS, subnets);

        // Create the netwoek using the SDN driver
        getDriver().createSubnet(networkId, networkName, subnetCidr);

        return networkId;
    }

    static {
        RendererHints.register(DOCKER_HOST, new RendererHints.NamedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
        RendererHints.register(SDN_PROVIDER, new RendererHints.NamedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
        RendererHints.register(SDN_AGENT, new RendererHints.NamedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
    }

}