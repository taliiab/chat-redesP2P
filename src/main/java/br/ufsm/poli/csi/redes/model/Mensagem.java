package br.ufsm.poli.csi.redes.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Mensagem {
    private TipoMensagem tipoMensagem;
    private String usuario;
    private String status;
    private String msg;

    public enum TipoMensagem {
        sonda, msg_individual, fim_chat, msg_grupo
    }
}
