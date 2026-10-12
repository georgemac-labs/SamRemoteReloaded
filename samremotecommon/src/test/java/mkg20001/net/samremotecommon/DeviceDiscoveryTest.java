package mkg20001.net.samremotecommon;

import org.junit.Test;
import java.util.List;

import static org.junit.Assert.*;

public class DeviceDiscoveryTest {
    @Test public void scansActualPrefixRatherThanAssuming24() {
        DeviceDiscovery.Subnet subnet = new DeviceDiscovery.Subnet("192.168.0.140", 25);
        List<String> hosts = subnet.scanHosts();
        assertEquals(125, hosts.size());
        assertTrue(hosts.contains("192.168.0.129"));
        assertTrue(hosts.contains("192.168.0.254"));
        assertFalse(hosts.contains("192.168.0.140"));
        assertFalse(hosts.contains("192.168.0.128"));
        assertFalse(hosts.contains("192.168.0.255"));
        assertFalse(subnet.contains("192.168.0.13"));
        assertTrue(subnet.contains("192.168.0.200"));
    }

    @Test public void handlesPointToPointAndHostPrefixes() {
        assertEquals(java.util.Collections.singletonList("10.0.0.11"),
                new DeviceDiscovery.Subnet("10.0.0.10", 31).scanHosts());
        assertTrue(new DeviceDiscovery.Subnet("10.0.0.10", 32).scanHosts().isEmpty());
        DeviceDiscovery.Subnet all = new DeviceDiscovery.Subnet("10.0.0.10", 0);
        assertTrue(all.contains("192.168.0.13"));
        assertTrue(all.scanHosts().isEmpty());
    }

    @Test public void largeNetworksDoNotCreateUnboundedScan() {
        assertTrue(new DeviceDiscovery.Subnet("172.16.0.1", 23).scanHosts().isEmpty());
        assertTrue(new DeviceDiscovery.Subnet("172.16.0.1", 16).scanHosts().isEmpty());
        assertEquals(253, new DeviceDiscovery.Subnet("172.16.0.1", 24).scanHosts().size());
    }

    @Test public void validatesIpv4WithoutDnsResolution() {
        assertTrue(DeviceDiscovery.isIpv4("192.168.0.13"));
        for (String value : new String[]{null, "", "example.com", "::1", "256.1.1.1",
                "1.2.3", "1.2.3.4.5", "-1.2.3.4", "1.2.3.4\n"}) {
            assertFalse(String.valueOf(value), DeviceDiscovery.isIpv4(value));
        }
        for (int prefix : new int[]{-1, 33}) {
            try {
                new DeviceDiscovery.Subnet("192.168.0.13", prefix);
                fail("Invalid prefix should fail");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void cancellationPreventsFurtherNetworkProbes() {
        DeviceDiscovery discovery = new DeviceDiscovery();
        discovery.close();
        assertNull(discovery.probe("192.0.2.1"));
        discovery.close();
    }
}
