package es.astillerosoftware.cuaderna;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Cada pocos minutos, mientras dura el modo coche: ¿sigue conectado el coche? */
public class CocheJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Coche.comprobar(getApplicationContext(), () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
