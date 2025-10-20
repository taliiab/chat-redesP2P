package br.ufsm.poli.csi.redes;

import br.ufsm.poli.csi.redes.swing.ChatClientSwing;

import java.net.UnknownHostException;

public class  Main {
    public static void main(String[] args) throws UnknownHostException {
        if(args.length != 3) {
            System.err.println("Argumentos incorretos");
            System.exit(1);
        }
        try {
            int portaOrigem = Integer.parseInt(args[0]);
            int portaDestino = Integer.parseInt(args[1]);
            String ipPadrao = args[2];
            new ChatClientSwing(portaOrigem, portaDestino, ipPadrao);
        } catch (NumberFormatException e) {
            System.err.println("Argumentos incorretos");
        }
    }
}