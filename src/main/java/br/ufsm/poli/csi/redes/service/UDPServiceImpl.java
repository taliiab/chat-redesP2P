package br.ufsm.poli.csi.redes.service;

import br.ufsm.poli.csi.redes.model.Mensagem;
import br.ufsm.poli.csi.redes.model.Usuario;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

import java.io.IOException;
import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

public abstract class UDPServiceImpl implements UDPService {

    private final String ipPadrao;
    protected DatagramSocket dtSocket;
    private int portaOrigem;
    protected int portaDestino;
    protected Usuario usuario = null;

    private final Map<String, Usuario> usuariosConectados = new ConcurrentHashMap<>();
    private final Map<String, Long> ultimoContato = new ConcurrentHashMap<>();
    private final Map<UDPServiceUsuarioListener, Boolean> usuarioListeners = new ConcurrentHashMap<>();

    private UDPServiceMensagemListener mensagemListener = null;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    public UDPServiceImpl(int portaOrigem, int portaDestino, String ipPadrao) throws UnknownHostException {
        this.ipPadrao = ipPadrao;
        this.portaOrigem = portaOrigem;
        this.portaDestino = portaDestino;

        try {
            this.dtSocket = new DatagramSocket(this.portaOrigem);
            this.dtSocket.setBroadcast(true);

            System.out.println("UDPServiceImpl estabelecido na porta: " + this.portaOrigem);
            System.out.println("Broadcast ativado para sondas.");

            new Thread(new EnviaSonda()).start();
            new Thread(new EscutaSonda()).start();
            new Thread(this::verificaTimeouts).start();

        } catch (SocketException e) {
            throw new RuntimeException("Erro ao estabelecer serviço UDP", e);
        }
    }

    // ------------------ MÉTODO DE REMOÇÃO DE USUÁRIO ------------------
    public void usuarioRemovido(Usuario usuario) {
        if (usuario != null && usuariosConectados.containsKey(usuario.getNome())) {
            usuariosConectados.remove(usuario.getNome());
            ultimoContato.remove(usuario.getNome());

            for (UDPServiceUsuarioListener listener : usuarioListeners.keySet()) {
                listener.usuarioRemovido(usuario);
            }

            System.out.println("Usuário removido: " + usuario.getNome());
        } else if (usuario != null) {
            System.out.println("Tentativa de remover usuário não conectado: " + usuario.getNome());
        }
    }

    // ------------------ VERIFICA TIMEOUT ------------------
    private void verificaTimeouts() {
        while (true) {
            try {
                Thread.sleep(5000);
                long agora = System.currentTimeMillis();

                for (String nome : new HashMap<>(usuariosConectados).keySet()) {
                    long ultimo = ultimoContato.getOrDefault(nome, agora);

                    if (agora - ultimo > 30000) { // 30s de inatividade
                        Usuario u = usuariosConectados.get(nome);
                        usuarioRemovido(u);
                        System.out.println("Usuário removido por inatividade: " + nome);
                    }
                }

                System.out.println("Usuarios conectados: " + usuariosConectados.keySet());

            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.out.println("Erro em verificaTimeouts: " + e.getMessage());
            }
        }
    }

    // ------------------ ENVIO DE SONDA ------------------
    private class EnviaSonda implements Runnable {
        @SneakyThrows
        @Override
        public void run() {
            while (true) {
                Thread.sleep(5000);
                if (usuario == null) continue;

                try {
                    Mensagem mensagem = new Mensagem();
                    mensagem.setTipoMensagem(Mensagem.TipoMensagem.sonda);
                    mensagem.setUsuario(usuario.getNome());
                    mensagem.setStatus(usuario.getStatus().toString());

                    String strMensagem = objectMapper.writeValueAsString(mensagem);
                    byte[] bMensagem = strMensagem.getBytes();

                    String baseIp = ipPadrao; // Ex: "192.168.83."

                    for (int i = 1; i < 255; i++) {
                        InetAddress destino = InetAddress.getByName(baseIp + i);

                        DatagramPacket pacote = new DatagramPacket(
                                bMensagem, bMensagem.length,
                                destino,
                                portaDestino
                        );
                        dtSocket.send(pacote);
                    }

                } catch (Exception e) {
                    System.out.println("Erro ao enviar mensagem de SONDA: " + e.getMessage());
                }
            }
        }
    }

    // ------------------ ESCUTA DE SONDA ------------------
    private class EscutaSonda implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    byte[] buffer = new byte[4096];
                    DatagramPacket pacoteRecebido = new DatagramPacket(buffer, buffer.length);

                    dtSocket.receive(pacoteRecebido);

                    String jsonRecebido = new String(pacoteRecebido.getData(), 0, pacoteRecebido.getLength());
                    Mensagem msg = objectMapper.readValue(jsonRecebido, Mensagem.class);

                    Usuario remetente = new Usuario(
                            msg.getUsuario(),
                            Usuario.StatusUsuario.valueOf(msg.getStatus() != null ? msg.getStatus() : "DISPONIVEL"),
                            pacoteRecebido.getAddress()
                    );

                    if (usuario != null && usuario.equals(remetente)) continue;

                    switch (msg.getTipoMensagem()) {
                        case sonda:
                            usuariosConectados.put(remetente.getNome(), remetente);
                            ultimoContato.put(remetente.getNome(), System.currentTimeMillis());
                            for (UDPServiceUsuarioListener listener : usuarioListeners.keySet()) {
                                listener.usuarioAdicionado(remetente);
                            }
                            break;

                        case msg_individual:
                            if (mensagemListener != null) {
                                mensagemListener.mensagemRecebida(msg.getMsg(), remetente, false);
                            }
                            break;

                        case msg_grupo:
                            if (mensagemListener != null) {
                                mensagemListener.mensagemRecebida(msg.getMsg(), remetente, true);
                            }
                            break;

                        case fim_chat:
                            if (mensagemListener != null) {
                                mensagemListener.fimChatPelaOutraParte(remetente);
                            }
                            break;
                    }

                } catch (Exception e) {
                    System.out.println("Erro em EscutaSonda: " + e.getMessage());
                }
            }
        }
    }

    // ------------------ ENVIO DE MENSAGEM ------------------
    @Override
    public void enviarMensagem(String mensagem, Usuario destinatario, boolean chatGeral) {
        new Thread(() -> {
            try {
                Mensagem.TipoMensagem tipo = chatGeral ? Mensagem.TipoMensagem.msg_grupo : Mensagem.TipoMensagem.msg_individual;

                Mensagem objMsg = Mensagem.builder()
                        .tipoMensagem(tipo)
                        .usuario(this.usuario.getNome())
                        .status(this.usuario.getStatus().toString())
                        .msg(mensagem)
                        .build();

                String jsonMsg = objectMapper.writeValueAsString(objMsg);
                byte[] buffer = jsonMsg.getBytes();

                if (chatGeral) {
                    String baseIp = ipPadrao;
                    for (int i = 1; i < 255; i++) {
                        InetAddress destino = InetAddress.getByName(baseIp + i);
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, destino, portaDestino);
                        dtSocket.send(packet);
                    }
                } else {
                    InetAddress destino = destinatario.getEndereco();
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, destino, portaDestino);
                    dtSocket.send(packet);
                }

            } catch (Exception e) {
                System.out.println("Erro ao enviar mensagem: " + e.getMessage());
            }
        }).start();
    }

    // ------------------ OUTROS MÉTODOS ------------------
    @Override
    public void usuarioAlterado(Usuario usuario) {
        this.usuario = usuario;
    }

    @Override
    public void addListenerMensagem(UDPServiceMensagemListener listener) {
        this.mensagemListener = listener;
    }

    @Override
    public void addListenerUsuario(UDPServiceUsuarioListener listener) {
        this.usuarioListeners.put(listener, true);
    }
}
