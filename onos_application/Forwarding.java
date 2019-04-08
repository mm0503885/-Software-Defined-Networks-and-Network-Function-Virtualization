/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sdn.sdnapp;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Service;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;


import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.host.HostService;
import org.onosproject.net.topology.TopologyService;

import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.PortNumber;
import org.onosproject.net.DeviceId;

import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;

import org.onosproject.net.flowobjective.FlowObjectiveService;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class Forwarding{

    private final Logger log = LoggerFactory.getLogger(getClass());
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    private  HashMap<DeviceId, HashMap<MacAddress, PortNumber>> OuterMap = new HashMap<DeviceId, HashMap<MacAddress, PortNumber>>();

    private ApplicationId appId;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("myapp");

        packetService.addProcessor(processor, PacketProcessor.director(2));
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        packetService.requestPackets(selector
                .matchEthType(Ethernet.TYPE_IPV4).build(), PacketPriority.REACTIVE, appId);
        packetService.requestPackets(selector
                .matchEthType(Ethernet.TYPE_ARP).build(), PacketPriority.REACTIVE, appId);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }
	
	private class ReactivePacketProcessor implements PacketProcessor {

    @Override
    public void process(PacketContext context) {
        // Stop processing if the packet has been handled, since we
        // can't do any more to it.
        if (context.isHandled()) {
            return;
        }
        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();

        if (ethPkt == null) {
            return;
        }

        MacAddress SourceMacAddress = ethPkt.getSourceMAC();
        MacAddress DstMacAddress = ethPkt.getDestinationMAC();
        PortNumber InPort  = pkt.receivedFrom().port();
        DeviceId SwitchId = pkt.receivedFrom().deviceId();
		
		if(!OuterMap.containsKey(SwitchId)) {
			
		  OuterMap.put(SwitchId, new HashMap<MacAddress,PortNumber>());
		}
		
		
		HashMap<MacAddress,PortNumber> InnerMap = OuterMap.get(SwitchId);
		InnerMap.put(SourceMacAddress,InPort);
		
		if(InnerMap.containsKey(DstMacAddress))
		{
           PortNumber outport = InnerMap.get(DstMacAddress);
           installRule(context,outport);
		}
		else
		{
		  flood(context);
		}
	  }
    }
	
	private void flood(PacketContext context) {
        if (topologyService.isBroadcastPoint(topologyService.currentTopology(),
                                             context.inPacket().receivedFrom())) {
            packetOut(context, PortNumber.FLOOD);
        } else {
            context.block();
        }
    }
    private void packetOut(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }
	
	private void installRule(PacketContext context, PortNumber portNumber) {
    //
    // We don't support (yet) buffer IDs in the Flow Service so
    // packet out first.
    //
    Ethernet inPkt = context.inPacket().parsed();
    TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
	
	selectorBuilder.matchEthDst(inPkt.getDestinationMAC());
					
    TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .setOutput(portNumber)
            .build();

    ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
            .withSelector(selectorBuilder.build())
            .withTreatment(treatment)
            .withPriority(40001)
            .withFlag(ForwardingObjective.Flag.VERSATILE)
            .fromApp(appId)
            .makeTemporary(10)
            .add();

    flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(),
                                 forwardingObjective);
    packetOut(context, PortNumber.TABLE);

    }

}
