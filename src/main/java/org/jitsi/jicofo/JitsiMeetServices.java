/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.jicofo;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.discovery.Version;
import org.jitsi.jicofo.event.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;

import org.jxmpp.jid.*;
import org.osgi.framework.*;

import java.util.*;

/**
 * Class manages discovery of Jitsi Meet application services like
 * jitsi-videobridge, recording, SIP gateway and so on...
 *
 * @author Pawel Domas
 */
public class JitsiMeetServices
    extends EventHandlerActivator
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(JitsiMeetServices.class);

    /**
     * The set of features sufficient for a node to be recognized as a
     * jitsi-videobridge.
     */
    public static final String[] VIDEOBRIDGE_FEATURES = new String[]
        {
            ColibriConferenceIQ.NAMESPACE,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_DTLS_SRTP,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_ICE_UDP_1,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_RAW_UDP_0
        };

    /**
     * The XMPP Service Discovery features of MUC service provided by the XMPP
     * server.
     */
    private static final String[] MUC_FEATURES
        = { "http://jabber.org/protocol/muc" };

    /**
     * Features advertised by SIP gateway component.
     */
    private static final String[] SIP_GW_FEATURES = new String[]
        {
            "http://jitsi.org/protocol/jigasi",
            "urn:xmpp:rayo:0"
        };

    /**
     * Features used to recognize pub-sub service.
     */
    /*private static final String[] PUBSUB_FEATURES = new String[]
        {
            "http://jabber.org/protocol/pubsub",
            "http://jabber.org/protocol/pubsub#subscribe"
        };*/

    /**
     * Manages Jitsi Videobridge component XMPP addresses.
     */
    private final BridgeSelector bridgeSelector;

    /**
     * Instance of {@link JibriDetector} which manages Jibri instances.
     */
    private JibriDetector jibriDetector;

    /**
     * Instance of {@link JigasiDetector} which manages Jigasi instances.
     */
    private JigasiDetector jigasiDetector;

    /**
     * {@link JibriDetector} which manages Jibri SIP instances
     * (video SIP gateway).
     */
    private JibriDetector sipJibriDetector;

    /**
     * The name of XMPP domain to which Jicofo user logs in.
     */
    private final DomainBareJid jicofoUserDomain;

    /**
     * The {@link ProtocolProviderHandler} for Jicofo XMPP connection.
     */
    private final ProtocolProviderHandler protocolProvider;

    /**
     * SIP gateway component XMPP address.
     */
    private Jid sipGateway;

    /**
     * The address of MUC component served by our XMPP domain.
     */
    private Jid mucService;

    /**
     * <tt>Version</tt> IQ instance holding detected XMPP server's version
     * (if any).
     */
    private Version XMPPServerVersion;

    /**
     * Returns <tt>true</tt> if given list of features complies with JVB feature
     * list.
     * @param features the list of feature to be checked.
     */
    static public boolean isJitsiVideobridge(List<String> features)
    {
        return DiscoveryUtil.checkFeatureSupport(
                VIDEOBRIDGE_FEATURES, features);
    }

    /**
     * Creates new instance of <tt>JitsiMeetServices</tt>
     *  @param protocolProviderHandler {@link ProtocolProviderHandler} for Jicofo
     *        XMPP connection.
     * @param jicofoUserDomain the name of the XMPP domain to which Jicofo user
     */
    public JitsiMeetServices(ProtocolProviderHandler protocolProviderHandler,
                             DomainBareJid jicofoUserDomain)
    {
        super(new String[] { BridgeEvent.HEALTH_CHECK_FAILED });

        Objects.requireNonNull(
            protocolProviderHandler, "protocolProviderHandler");

        OperationSetSubscription subscriptionOpSet
            = protocolProviderHandler.getOperationSet(
                    OperationSetSubscription.class);

        Objects.requireNonNull(subscriptionOpSet, "subscriptionOpSet");

        this.jicofoUserDomain = jicofoUserDomain;
        this.protocolProvider = protocolProviderHandler;
        this.bridgeSelector = new BridgeSelector(subscriptionOpSet);
    }

    /**
     * Called by other classes when they detect JVB instance.
     * @param bridgeJid the JID of discovered JVB component.
     */
    void newBridgeDiscovered(Jid bridgeJid, Version version)
    {
        bridgeSelector.addJvbAddress(bridgeJid, version);
    }

    /**
     * Call when new component becomes available.
     *
     * @param node component XMPP address
     * @param features list of features supported by <tt>node</tt>
     * @param version the <tt>Version</tt> IQ which carries the info about
     *                <tt>node</tt> version(if any).
     */
    void newNodeDiscovered(Jid node,
                           List<String> features,
                           Version version)
    {
        if (isJitsiVideobridge(features))
        {
            newBridgeDiscovered(node, version);
        }
        else if (sipGateway == null
            && DiscoveryUtil.checkFeatureSupport(SIP_GW_FEATURES, features))
        {
            logger.info("Discovered SIP gateway: " + node);

            setSipGateway(node);
        }
        else if (mucService == null
            && DiscoveryUtil.checkFeatureSupport(MUC_FEATURES, features))
        {
            logger.info("MUC component discovered: " + node);

            setMucService(node);
        }
        else if (jicofoUserDomain != null && jicofoUserDomain.equals(node))
        {
            this.XMPPServerVersion = version;

            logger.info("Detected XMPP server version: " + version);
        }
        /*
        FIXME: pub-sub service auto-detect ?
        else if (capsOpSet.hasFeatureSupport(item, PUBSUB_FEATURES))
        {
            // Potential PUBSUB service
            logger.info("Potential PUBSUB service:" + item);
            List<String> subItems = capsOpSet.getItems(item);
            for (String subItem: subItems)
            {
                logger.info("Subnode " + subItem + " of " + item);
                capsOpSet.hasFeatureSupport(
                    item, subItem, VIDEOBRIDGE_FEATURES);
            }
        }*/
    }

    /**
     * Call when a component goes offline.
     *
     * @param node XMPP address of disconnected XMPP component.
     */
    void nodeNoLongerAvailable(Jid node)
    {
        if (bridgeSelector.isJvbOnTheList(node))
        {
            bridgeSelector.removeJvbAddress(node);
        }
        else if (node.equals(sipGateway))
        {
            logger.warn("SIP gateway went offline: " + node);

            sipGateway = null;
        }
        else if (node.equals(mucService))
        {
            logger.warn("MUC component went offline: " + node);

            mucService = null;
        }
    }

    /**
     * Sets new XMPP address of the SIP gateway component.
     * @param sipGateway the XMPP address to be set as SIP gateway component
     *                   address.
     */
    void setSipGateway(Jid sipGateway)
    {
        this.sipGateway = sipGateway;
    }

    /**
     * Returns XMPP address of SIP gateway component.
     */
    public Jid getSipGateway()
    {
        return sipGateway;
    }

    /**
     * Returns Jibri SIP detector if available.
     * @return {@link JibriDetector} or <tt>null</tt> if not configured.
     */
    public JibriDetector getSipJibriDetector()
    {
        return sipJibriDetector;
    }

    /**
     * Returns {@link JibriDetector} instance that manages Jibri pool used by
     * this Jicofo process or <tt>null</tt> if unavailable in the current
     * session.
     */
    public JibriDetector getJibriDetector()
    {
        return jibriDetector;
    }

    /**
     * Returns {@link JigasiDetector} instance that manages Jigasi pool used by
     * this Jicofo process or <tt>null</tt> if unavailable in the current
     * session.
     */
    public JigasiDetector getJigasiDetector()
    {
        return jigasiDetector;
    }

    /**
     * Returns {@link BridgeSelector} bound to this instance that can be used to
     * select the videobridge on the xmppDomain handled by this instance.
     */
    public BridgeSelector getBridgeSelector()
    {
        return bridgeSelector;
    }

    /**
     * Returns the address of MUC component for our XMPP domain.
     */
    public Jid getMucService()
    {
        return mucService;
    }

    /**
     * Sets the address of MUC component.
     * @param mucService component sub domain that refers to MUC
     */
    public void setMucService(Jid mucService)
    {
        this.mucService = mucService;
    }

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        bridgeSelector.init();

        super.start(bundleContext);

        ConfigurationService config = FocusBundleActivator.getConfigService();
        String jibriBreweryName
            = config.getString(JibriDetector.JIBRI_ROOM_PNAME);

        if (!StringUtils.isNullOrEmpty(jibriBreweryName))
        {
            jibriDetector
                = new JibriDetector(protocolProvider, jibriBreweryName, false);

            jibriDetector.init();
        }

        String jigasiBreweryName
            = config.getString(JigasiDetector.JIGASI_ROOM_PNAME);
        if (!StringUtils.isNullOrEmpty(jigasiBreweryName))
        {
            jigasiDetector
                = new JigasiDetector(protocolProvider, jigasiBreweryName);

            jigasiDetector.init();
        }

        String jibriSipBreweryName
            = config.getString(JibriDetector.JIBRI_SIP_ROOM_PNAME);
        if (!StringUtils.isNullOrEmpty(jibriSipBreweryName))
        {
            sipJibriDetector
                = new JibriDetector(
                        protocolProvider, jibriSipBreweryName, true);
            sipJibriDetector.init();
        }
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        if (jibriDetector != null)
        {
            jibriDetector.dispose();
            jibriDetector = null;
        }

        if (jigasiDetector != null)
        {
            jigasiDetector.dispose();
            jigasiDetector = null;
        }

        if (sipJibriDetector != null)
        {
            sipJibriDetector.dispose();
            sipJibriDetector = null;
        }

        bridgeSelector.dispose();

        super.stop(bundleContext);
    }

    @Override
    public void handleEvent(Event event)
    {
        if (BridgeEvent.HEALTH_CHECK_FAILED.equals(event.getTopic()))
        {
            BridgeEvent bridgeEvent = (BridgeEvent) event;

            bridgeSelector.removeJvbAddress(bridgeEvent.getBridgeJid());
        }
    }

    /**
     * The version of XMPP server to which Jicofo user is connecting to.
     *
     * @return {@link Version} instance which holds the version details. Can be
     *         <tt>null</tt> if not discovered yet.
     */
    public Version getXMPPServerVersion()
    {
        return XMPPServerVersion;
    }

    /**
     * Finds the version of the videobridge identified by given
     * <tt>bridgeJid</tt>.
     *
     * @param bridgeJid the XMPP address of the videobridge for which we want to
     *        obtain the version.
     *
     * @return {@link Version} instance which holds the details about JVB
     *         version or <tt>null</tt> if unknown.
     */
    public Version getBridgeVersion(Jid bridgeJid)
    {
        return bridgeSelector.getBridgeVersion(bridgeJid);
    }
}
