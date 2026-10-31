package es.astillerosoftware.cuaderna;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Tarea en segundo plano: cada media hora mira si toca enviar el resumen del día. */
public class ResumenJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { Correo.enviarSiToca(getApplicationContext(), false); }
            finally { jobFinished(params, false); }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
