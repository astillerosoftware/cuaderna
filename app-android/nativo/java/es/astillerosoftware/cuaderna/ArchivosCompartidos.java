package es.astillerosoftware.cuaderna;

import androidx.core.content.FileProvider;

/** Da acceso temporal a los PDF que se comparten desde Cuaderna (carpeta de caché «compartir»). */
public class ArchivosCompartidos extends FileProvider {
    public ArchivosCompartidos() {
        super(R.xml.chispa_compartir);
    }
}
