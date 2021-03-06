/*******************************************************************************
 * Copyright (c) Intel Corporation
 * Copyright (c) 2017
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.osc.core.broker.service.tasks.conformance.openstack.securitygroup;

import java.util.Arrays;

import javax.persistence.EntityManager;

import org.apache.log4j.Logger;
import org.osc.core.broker.model.entities.appliance.DistributedApplianceInstance;
import org.osc.core.broker.model.entities.virtualization.SecurityGroupMember;
import org.osc.core.broker.model.entities.virtualization.SecurityGroupMemberType;
import org.osc.core.broker.model.entities.virtualization.openstack.Network;
import org.osc.core.broker.model.entities.virtualization.openstack.Subnet;
import org.osc.core.broker.model.entities.virtualization.openstack.VM;
import org.osc.core.broker.model.entities.virtualization.openstack.VMPort;
import org.osc.core.broker.model.plugin.ApiFactoryService;
import org.osc.core.broker.model.plugin.sdncontroller.NetworkElementImpl;
import org.osc.core.broker.service.persistence.OSCEntityManager;
import org.osc.core.broker.service.tasks.TransactionalTask;
import org.osc.core.broker.service.tasks.conformance.openstack.securitygroup.element.PortGroup;
import org.osc.sdk.controller.DefaultInspectionPort;
import org.osc.sdk.controller.DefaultNetworkPort;
import org.osc.sdk.controller.api.SdnRedirectionApi;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(service = VmPortHookRemoveTask.class)
public class VmPortHookRemoveTask extends TransactionalTask {

    private final Logger log = Logger.getLogger(VmPortHookRemoveTask.class);

    private SecurityGroupMember sgm;
    private String serviceName;
    private VMPort vmPort;
    private DistributedApplianceInstance dai;

    @Reference
    private ApiFactoryService apiFactoryService;

    public VmPortHookRemoveTask create(SecurityGroupMember sgm, VMPort vmPort, DistributedApplianceInstance daiToRedirectTo,
            String serviceName) {
        VmPortHookRemoveTask task = new VmPortHookRemoveTask();
        task.vmPort = vmPort;
        task.dai = daiToRedirectTo;
        task.serviceName = serviceName;
        task.sgm = sgm;
        task.apiFactoryService = this.apiFactoryService;
        task.dbConnectionManager = this.dbConnectionManager;
        task.txBroadcastUtil = this.txBroadcastUtil;

        return task;
    }

    @Override
    public void executeTransaction(EntityManager em) throws Exception {
        this.vmPort = em.find(VMPort.class, this.vmPort.getId());
        this.sgm = em.find(SecurityGroupMember.class, this.sgm.getId());
        if (this.dai != null) {
            this.dai = OSCEntityManager.loadPessimistically(em, this.dai);

            VM vm = this.sgm.getVm();
            Network network = this.sgm.getNetwork();
            Subnet subnet = this.sgm.getSubnet();

            if (this.sgm.getType() == SecurityGroupMemberType.VM && vm != null) {
                this.log.info(String.format(
                        "Removing Inspection Hooks for Security Group VM Member '%s' for service '%s'",
                        this.sgm.getMemberName(), this.serviceName));
            } else if (this.sgm.getType() == SecurityGroupMemberType.NETWORK && network != null) {
                this.log.info(String
                        .format("Removing Inspection Hooks for Port with mac '%s' belonging to Security Group Network Member '%s' for service '%s'",
                                this.vmPort.getMacAddresses(), this.sgm.getMemberName(), this.serviceName));
            } else if (this.sgm.getType() == SecurityGroupMemberType.SUBNET && subnet != null) {
                this.log.info(String
                        .format("Removing Inspection Hooks for Port with mac '%s' belonging to Security Group Subnet Member '%s' for service '%s'",
                                this.vmPort.getMacAddresses(), this.sgm.getMemberName(), this.serviceName));
            }

            try (SdnRedirectionApi controller = this.apiFactoryService.createNetworkRedirectionApi(this.dai);) {
                DefaultNetworkPort ingressPort = new DefaultNetworkPort(this.dai.getInspectionOsIngressPortId(),
                        this.dai.getInspectionIngressMacAddress());
                DefaultNetworkPort egressPort = new DefaultNetworkPort(this.dai.getInspectionOsEgressPortId(),
                        this.dai.getInspectionEgressMacAddress());
                if (this.apiFactoryService.supportsPortGroup(this.dai.getVirtualSystem())){
                    String portGroupId = this.sgm.getSecurityGroup().getNetworkElementId();
                    PortGroup portGroup = new PortGroup();
                    portGroup.setPortGroupId(portGroupId);
                    //Element object in DefaultInpectionPort will only be used in case of SFC , hence pass null
                    controller.removeInspectionHook(Arrays.asList(portGroup), new DefaultInspectionPort(ingressPort, egressPort, null));
                } else {
                    controller.removeInspectionHook(Arrays.asList(new NetworkElementImpl(this.vmPort)), new DefaultInspectionPort(ingressPort, egressPort, null));
                }
            }
            this.vmPort.removeDai(this.dai);
            OSCEntityManager.update(em, this.vmPort, this.txBroadcastUtil);
        }
    }

    @Override
    public String getName() {
        if (this.sgm.getType() == SecurityGroupMemberType.VM && this.sgm.getVm() != null) {
            return String.format("Removing Inspection Hooks for Security Group VM Member '%s' for service '%s'",
                    this.sgm.getMemberName(), this.serviceName);
        } else if (this.sgm.getType() == SecurityGroupMemberType.NETWORK && this.sgm.getNetwork() != null) {
            return String
                    .format("Removing Inspection Hooks for Port with MAC '%s' belonging to Security Group Network Member '%s' for service '%s'",
                            this.vmPort.getMacAddresses(), this.sgm.getMemberName(), this.serviceName);
        } else if (this.sgm.getType() == SecurityGroupMemberType.SUBNET && this.sgm.getSubnet() != null) {
            return String
                    .format("Removing Inspection Hooks for Port with MAC '%s' belonging to Security Group Subnet Member '%s' for service '%s'",
                            this.vmPort.getMacAddresses(), this.sgm.getMemberName(), this.serviceName);
        }
        // We should never get here
        throw new IllegalStateException(
                "Vm Hook Remove needs to specify either the network or vm on behalf of which its running");
    }

}
