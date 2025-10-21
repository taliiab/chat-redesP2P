package br.ufsm.poli.csi.redes;

import br.ufsm.poli.csi.redes.swing.ChatClientSwing;
import java.net.UnknownHostException;

public class Main {
    public static void main(String[] args) throws UnknownHostException {
        try {
            int portaOrigem = Integer.parseInt(args[0]); //escuta mensagem
            int portaDestino = Integer.parseInt(args[1]); //envia mensagem
            String ipPadrao = args[2]; //endereço
            new ChatClientSwing(portaOrigem, portaDestino, ipPadrao); //cria e passa informações

        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.out.println("ERRO AO INICIAR!! VERIFIQUE AS VARIÁVEIS."); //argumentos inválidos ou não passados
            System.exit(1);
        }
    }
}