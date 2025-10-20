package br.ufsm.poli.csi.redes.model;

import br.ufsm.poli.csi.redes.swing.ChatClientSwing;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.net.InetAddress;

@Data //get(), set(), toString(), equals(), hashCode()
@AllArgsConstructor
@NoArgsConstructor
public class Usuario {

    private String nome;
    private StatusUsuario status;
    private InetAddress endereco;


    //2 objetos Usuario sao iguais se tiverem o mesmo nome
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Usuario usuario = (Usuario) o;
        return nome.equals(usuario.nome);
    }

    @Override
    public int hashCode() {
        return nome.hashCode();
    }

    public String toString() {
        return this.getNome() + " (" + getStatus().toString() + ")";
    }

    //enum -- define um conjunto de constantes
    public enum StatusUsuario {
        DISPONIVEL, NAO_PERTURBE, VOLTO_LOGO
    }

}
