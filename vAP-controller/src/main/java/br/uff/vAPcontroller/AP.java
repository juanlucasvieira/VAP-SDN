package br.uff.vAPcontroller;

import br.uff.vAPcontroller.Cmds;
import br.uff.vAPcontroller.CtrlInterface;
import br.uff.vAPcontroller.Observer;
import br.uff.vAPcontroller.TransactionHandler;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AP implements Observer {

    private CtrlInterface gci;
    private HashMap<String, PhyIface> phy_ifaces;
    private HashMap<String, CtrlInterface> availableCtrlIfaces;
    private String ap_id;
    private TransactionHandler handler;

    public AP(String id, InetAddress ip, int port) {

    }

    AP(String id, InetAddress ip, int port, TransactionHandler handler) {
        this.ap_id = id;
        this.gci = new CtrlInterface(ip, port);
        this.handler = handler;
        this.availableCtrlIfaces = new HashMap<>();
        this.phy_ifaces = new HashMap<>();
    }

    @Override
    public String getId() {
        return ap_id;
    }

    public String getAddress() {
        return gci.getIp().getHostAddress() + "#" + gci.getPort();
    }

    public CtrlInterface getGci() {
        return gci;
    }

//    public void vAPUpdate() {
//        for (VirtualAP vap : vaps.values()) {
//            vap.update(handler);
//        }
//    }
    public void requestInfo() {
        if (!handler.isObserverRegistered(this)) {
            handler.registerObserver(this);
        }
        if (!gci.isCookieSet()) {
            gci.requestCookie(handler);
        } else {
            handler.pushAsyncTransaction(new Transaction(this.ap_id, Cmds.GET_AP_IFACES, gci, Transaction.ASYNC));
            for (CtrlInterface ctrl_iface : availableCtrlIfaces.values()) {
                if (!ctrl_iface.isCookieSet()) {
                    ctrl_iface.requestCookie(handler);
                } else {
                    handler.pushAsyncTransaction(new Transaction(this.ap_id, Cmds.REQ_STATUS_INFO, ctrl_iface, Transaction.ASYNC));
                }
                for (PhyIface phy : phy_ifaces.values()) {
                    phy.update(handler);
                }
            }

        }
    }

    @Override
    public void notify(Transaction t) {
        switch (t.getRequest()) {
            case Cmds.GET_AP_IFACES:
                discoverCtrlIfaces(t.getResponse(), t.getDestination());
                break;
            case Cmds.REQ_STATUS_INFO:
                discoverPhysicalAndVirtualIfaces(t.getResponse());
                break;
        }
    }

    private void discoverCtrlIfaces(String response, CtrlInterface iface) {
        //wlp9s0 ctrl_iface=udp:8881
        String[] lines = response.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String interfaceName = line.split(" ")[0];
            int port = Integer.parseInt(line.substring(line.lastIndexOf(':') + 1));
            String iface_id = this.gci.getIp().getHostAddress() + ":" + port;
            if (!availableCtrlIfaces.containsKey(iface_id)) {
                availableCtrlIfaces.put(iface_id, new CtrlInterface(this.gci.getIp(), port));
            }
        }
    }

    private void discoverPhysicalAndVirtualIfaces(String response) {
//        availableCtrlIfaces.get(iface.getId());
        String[] lines = response.split("\n");
        int errors = 0;
        int endOfPhyIfaceInfo = -1;
        boolean foundInfo = false;
        for (int i = 0; i < lines.length; i++) {
            if (!foundInfo) {
                if (lines[i].startsWith("bss[")) {
                    endOfPhyIfaceInfo = i;
                    errors += parsePhyIfaceInfo(lines, endOfPhyIfaceInfo);
                    foundInfo = true;
                }
            }
        }

    }

    public HashMap<String, PhyIface> getPhy_ifaces() {
        return phy_ifaces;
    }

    public HashMap<String, CtrlInterface> getAvailableCtrlIfaces() {
        return availableCtrlIfaces;
    }
    
    

    private int parsePhyIfaceInfo(String[] lines, int endOfPhyIfaceInfo) {
        /*
        state=ENABLED
        phy=wlp9s0
        freq=2412
        num_sta_non_erp=0
        num_sta_no_short_slot_time=1
        num_sta_no_short_preamble=0
        olbc=0
        num_sta_ht_no_gf=0
        num_sta_no_ht=0
        num_sta_ht_20_mhz=0
        num_sta_ht40_intolerant=0
        olbc_ht=0
        ht_op_mode=0x0
        cac_time_seconds=0
        cac_time_left_seconds=N/A
        channel=1
        secondary_channel=0
        ieee80211n=0
        ieee80211ac=0
        beacon_int=100
        dtim_period=2
        supported_rates=02 04 0b 16
        max_txpower=20
         */
        String phy_iface_name = "";
        boolean state = false;
        int frequency = -1;
        int channel = -1;
        int[] supported_rates = null;
        int max_txpower = -1;

        for (int i = 0; i < endOfPhyIfaceInfo; i++) {
            if (lines[i].startsWith("state")) {
                if (lines[i].split("=")[1].equalsIgnoreCase("enabled")) {
                    state = true;
                } else {
                    state = false;
                }
            } else if (lines[i].startsWith("phy")) {
                phy_iface_name = lines[i].split("=")[1];
            } else if (lines[i].startsWith("freq")) {
                frequency = Integer.parseInt(lines[i].split("=")[1]);
            } else if (lines[i].startsWith("channel")) {
                channel = Integer.parseInt(lines[i].split("=")[1]);
            } else if (lines[i].startsWith("supported_rates")) {
                String[] rates = lines[i].split("=")[1].split(" ");
                supported_rates = new int[rates.length];
                for (int j = 0; j < rates.length; j++) {
                    supported_rates[j] = Integer.parseInt(rates[j], 16);
                }
            } else if (lines[i].startsWith("max_txpower")) {
                max_txpower = Integer.parseInt(lines[i].split("=")[1]);
            }
        }
        if (frequency > 0 && channel > 0
                && max_txpower > 0 && phy_iface_name.length() > 0
                && (supported_rates != null && supported_rates.length > 0)) {
            PhyIface phy;
            if (!phy_ifaces.containsKey(phy_iface_name)) {
                phy = new PhyIface(phy_iface_name, frequency, channel, state, supported_rates, max_txpower);
                phy_ifaces.put(phy.getIface_name(), phy);
            } else {
                phy = phy_ifaces.get(phy_iface_name);
                phy.setIface_name(phy_iface_name);
                phy.setFrequency(frequency);
                phy.setChannel(channel);
                phy.setState(state);
                phy.setSupported_rates(supported_rates);
                phy.setMax_txpower(max_txpower);
            }
            parseVAPsInfo(phy, lines, endOfPhyIfaceInfo);
            return 0;
        } else {
            Log.print(Log.ERROR, "Error in physical interface information filling.");
            return 1;
        }
    }

    private void parseVAPsInfo(PhyIface phy, String[] lines, int startOfVAPInfo) {
        /*
        bss[0]=wlp9s0
        bssid[0]=2c:d0:5a:42:73:13
        ctrl_interface[0]=udp:8881
        ssid[0]=TESTE_CSA_CH1
        num_sta[0]=1
         */
        int j = 0;
        while (j < lines.length) {
            String v_iface_name = "", bss = "", ssid = "";
            int port = -1;
            short sta_number = -1;

            for (j = startOfVAPInfo; j < (startOfVAPInfo + 5); j++) {
                if (lines[j].startsWith("bss[")) {
                    v_iface_name = lines[j].split("=")[1];
                } else if (lines[j].startsWith("bssid[")) {
                    bss = lines[j].split("=")[1];
                } else if (lines[j].startsWith("ctrl_interface[")) {
                    port = Integer.parseInt(lines[j].split(":")[1]);
                } else if (lines[j].startsWith("ssid[")) {
                    ssid = lines[j].split("=")[1];
                } else if (lines[j].startsWith("num_sta[")) {
                    sta_number = Short.parseShort(lines[j].split("=")[1]);
                }
            }
            if (sta_number >= 0 && bss.length() > 0 && ssid.length() > 0) {
                VirtualAP vap = phy.getVAPByName(v_iface_name);
                if (vap == null) {
                    phy.addVAP(new VirtualAP(UUID.randomUUID().toString().replaceAll("-", ""),
                            v_iface_name, bss, new CtrlInterface(this.gci.getIp(), port), ssid, sta_number));
                } else {
                    vap.setNum_sta(sta_number);
                    vap.setSsid(ssid);
                }
            } else {
                Log.print(Log.ERROR, "Error in virtual AP information filling.");
            }
            startOfVAPInfo = j;
        }
    }

//    private void parseVAPsInfo(PhyIface phy, String[] lines, int endOfPhyIfaceInfo) {
//        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
//    }
}
