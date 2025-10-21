package br.ufsm.poli.csi.redes.service;

import br.ufsm.poli.csi.redes.model.Mensagem;
import br.ufsm.poli.csi.redes.model.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

import java.net.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class UDPServiceImpl implements UDPService {

    private static final Logger LOGGER = Logger.getLogger(UDPServiceImpl.class.getName());
    private static final long SONDA_INTERVALO_MS = 5000; //intervalo de envio
    private static final long TIMEOUT_INATIVIDADE_MS = 30000; //inatividade

    protected DatagramSocket dtSocket;
    private final int portaOrigem;
    protected final int portaDestino;
    private final InetAddress broadcastAddress;

    protected Usuario usuario;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    //gerenciamento e atividades
    private final Map<String, Usuario> usuariosConectados = new ConcurrentHashMap<>();
    private final Map<String, Long> ultimoContato = new ConcurrentHashMap<>();
    private final Map<UDPServiceUsuarioListener, Boolean> usuarioListeners = new ConcurrentHashMap<>();
    private UDPServiceMensagemListener mensagemListener;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2); //temporizador

    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor(); //envia mensagem


    //CONSTRUTOR
    public UDPServiceImpl(int portaOrigem, int portaDestino, String ipPadrao) throws UnknownHostException {
        this.portaOrigem = portaOrigem;
        this.portaDestino = portaDestino;

        //assume broadcast 255
        String broadcastIP = ipPadrao.substring(0, ipPadrao.lastIndexOf('.')) + ".255";
        this.broadcastAddress = InetAddress.getByName(broadcastIP);

        try {

            //cria o socket e habilita o broadcast
            this.dtSocket = new DatagramSocket(this.portaOrigem);
            this.dtSocket.setBroadcast(true);

            System.out.println("Porta: " + this.portaOrigem);
            System.out.println("Broadcast: " + this.broadcastAddress.getHostAddress());

            scheduler.scheduleAtFixedRate(this::enviaSondaBroadcast, 0, SONDA_INTERVALO_MS, TimeUnit.MILLISECONDS); //tempo de sonda

            scheduler.scheduleAtFixedRate(this::verificaTimeouts, 0, SONDA_INTERVALO_MS, TimeUnit.MILLISECONDS); //tempo inatividade

            new Thread(this::escutaPacotes, "UDP-Listener").start(); //thread que escuta pacotes

        } catch (SocketException e) {
            System.out.println("Erro ao estabelecer serviço UDP: " + e.getMessage());
            e.printStackTrace();
        }
    }

    //remoção de usua´rio
    public void usuarioRemovido(Usuario usuario) {
        if (usuario != null) {
            //remove usuário das listas
            if (usuariosConectados.remove(usuario.getNome()) != null) {
                ultimoContato.remove(usuario.getNome());
                //notificação de remoção
                usuarioListeners.keySet().forEach(listener -> listener.usuarioRemovido(usuario));
                System.out.println("Usuário removido: " + usuario.getNome());
            } else {
                System.out.println("Tentativa de remover usuário não conectado: " + usuario.getNome());
            }
        }
    }

    //timeout
    private void verificaTimeouts() {
        try {
            long agora = System.currentTimeMillis();

            //filtro para identificar usuários inativos
            usuariosConectados.keySet().stream()
                    .filter(nome -> agora - ultimoContato.getOrDefault(nome, agora) > TIMEOUT_INATIVIDADE_MS)
                    .map(usuariosConectados::get)
                    .toList() //lista antes de modificar o mapa
                    .forEach(u -> {
                        usuarioRemovido(u);
                        System.out.println("Usuário removido por inatividade: " + u.getNome());
                    });

            System.out.println("Usuários conectados: " + usuariosConectados.keySet());

        } catch (Exception e) {
            System.out.println("Erro em verificaTimeouts: " + e.getMessage());
            e.printStackTrace();        }
    }

    //envia sonda
    @SneakyThrows
    private void enviaSondaBroadcast() {
        if (usuario == null) return; //usuario deve estar configurado
        try {
            //objeto do tipo sonda
            Mensagem mensagem = new Mensagem();
            mensagem.setTipoMensagem(Mensagem.TipoMensagem.sonda);
            mensagem.setUsuario(usuario.getNome());
            mensagem.setStatus(usuario.getStatus().toString());

            //convertee mensagem p JSON e envia
            String strMensagem = objectMapper.writeValueAsString(mensagem);
            byte[] bMensagem = strMensagem.getBytes();

            DatagramPacket pacote = new DatagramPacket(
                    bMensagem, bMensagem.length,
                    broadcastAddress,
                    portaDestino
            );
            dtSocket.send(pacote);

            System.out.println("Sonda enviada por broadcast.");

        } catch (Exception e) {
            System.out.println("Erro ao enviar mensagem de SONDA: " + e.getMessage());
            e.printStackTrace();        }
    }

    //escuta pacotes
    private void escutaPacotes() {
        while (true) {
            try {
                byte[] buffer = new byte[4096];
                DatagramPacket pacoteRecebido = new DatagramPacket(buffer, buffer.length);

                dtSocket.receive(pacoteRecebido); //esperaa por mensagem

                //converte bytes em texto JSON e depois em objeto Mensagem
                String jsonRecebido = new String(pacoteRecebido.getData(), 0, pacoteRecebido.getLength());
                Mensagem msg = objectMapper.readValue(jsonRecebido, Mensagem.class);

                processaMensagemRecebida(msg, pacoteRecebido.getAddress()); //processa conteudo da mensagem

            } catch (SocketException e) {
                if (dtSocket.isClosed()) {
                    System.out.println("Escutador UDP finalizado.");
                    return;
                }
                System.out.println("Erro de Socket em EscutaPacotes: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Erro em EscutaPacotes: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    //mensagens recebidas
    private void processaMensagemRecebida(Mensagem msg, InetAddress remetenteEndereco) {

        //cria objeto usuario a partir da amensgaem
        Usuario remetente = new Usuario(
                msg.getUsuario(),
                Usuario.StatusUsuario.valueOf(msg.getStatus() != null ? msg.getStatus() : "DISPONIVEL"),
                remetenteEndereco
        );

        if (usuario != null && usuario.equals(remetente)) return; //ignora msg do próprio usuario

        //identifica tipo da mensagem
        switch (msg.getTipoMensagem()) {
            case sonda -> {
                //atualiza ou add usuario
                usuariosConectados.put(remetente.getNome(), remetente);
                ultimoContato.put(remetente.getNome(), System.currentTimeMillis());
                usuarioListeners.keySet().forEach(listener -> listener.usuarioAdicionado(remetente));
                System.out.println("Sonda recebida de: " + remetente.getNome());
            }
            case msg_individual -> {
                if (mensagemListener != null) {
                    mensagemListener.mensagemRecebida(msg.getMsg(), remetente, false);
                }
            }
            case msg_grupo -> {
                if (mensagemListener != null) {
                    mensagemListener.mensagemRecebida(msg.getMsg(), remetente, true);
                }
            }
            case fim_chat -> {
                if (mensagemListener != null) {
                    mensagemListener.fimChatPelaOutraParte(remetente);
                }
            }
        }
    }

    //envio de mensahgem
    @Override
    public void enviarMensagem(String mensagem, Usuario destinatario, boolean chatGeral) {
        senderExecutor.execute(() -> {
            try {
                //verifica usuario
                if (this.usuario == null) {
                    LOGGER.warning("Tentativa de enviar mensagem com usuário não configurado.");
                    return;
                }

                //tipo de mensagem
                Mensagem.TipoMensagem tipo = chatGeral ? Mensagem.TipoMensagem.msg_grupo : Mensagem.TipoMensagem.msg_individual;

                //cria objeto mensagem
                Mensagem objMsg = Mensagem.builder()
                        .tipoMensagem(tipo)
                        .usuario(this.usuario.getNome())
                        .status(this.usuario.getStatus().toString())
                        .msg(mensagem)
                        .build();

                //converte para JSON
                String jsonMsg = objectMapper.writeValueAsString(objMsg);
                byte[] buffer = jsonMsg.getBytes();

                //envia para destinatario
                InetAddress destino;
                if (chatGeral) {
                    destino = broadcastAddress;
                } else {
                    destino = destinatario.getEndereco();
                }

                //cria e envia pacote
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, destino, portaDestino);
                dtSocket.send(packet);

            } catch (Exception e) {
                System.out.println("Erro ao enviar mensagem: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

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