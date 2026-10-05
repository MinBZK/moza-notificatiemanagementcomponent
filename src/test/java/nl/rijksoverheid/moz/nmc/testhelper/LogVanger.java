package nl.rijksoverheid.moz.nmc.testhelper;

import org.jboss.logmanager.ExtLogRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Vangt logregels van één categorie op, zodat een test kan asserteren dát er gelogd is en op welk
 * niveau. Quarkus biedt hier geen testfaciliteit voor, vandaar deze handmatige handler.
 */
public final class LogVanger implements AutoCloseable {

    private final Logger logger;
    private final Handler handler;
    private final Level oorspronkelijkNiveau;
    private final List<LogRecord> regels = Collections.synchronizedList(new ArrayList<>());

    private LogVanger(String categorie) {
        this.logger = Logger.getLogger(categorie);
        this.handler = new Handler() {
            @Override
            public void publish(LogRecord regel) {
                regels.add(regel);
            }

            @Override
            public void flush() {
                // Niets te legen: de regels staan al in de lijst.
            }

            @Override
            public void close() {
                // De handler houdt geen bronnen vast.
            }
        };
        this.handler.setLevel(Level.ALL);
        // Ook op de logger, niet alleen op de handler. Zonder dit erft de categorie het
        // rootniveau (INFO) en kort jboss-logging een Log.debugf af vóórdat enige handler hem
        // ziet — de vanger zou dan stil niets opleveren en een test op een DEBUG-regel zou
        // slagen omdat er niets te vinden was.
        this.oorspronkelijkNiveau = logger.getLevel();
        this.logger.setLevel(Level.ALL);
        this.logger.addHandler(handler);
    }

    public static LogVanger van(Class<?> categorie) {
        return new LogVanger(categorie.getName());
    }

    /** Vangt ook de regels van de klassen in dit package op, via de loggerhiërarchie. */
    public static LogVanger vanPakket(Package pakket) {
        return new LogVanger(pakket.getName());
    }

    /**
     * Alle opgevangen regels van precies dit niveau, als tekst.
     * <p>
     * Exacte gelijkheid op het niveau, niet "minstens": een assertie dat er géén WARN is hoort
     * niet stilzwijgend te slagen doordat de regel naar SEVERE is verschoven.
     */
    public List<String> regelsOpNiveau(Level niveau) {
        synchronized (regels) {
            return regels.stream()
                    .filter(regel -> regel.getLevel().intValue() == niveau.intValue())
                    .map(LogVanger::tekst)
                    .toList();
        }
    }

    // jboss-logging formatteert pas bij het schrijven, dus getMessage() levert bij Log.warnf nog de
    // formatstring op; ExtLogRecord kent de ingevulde variant.
    private static String tekst(LogRecord regel) {
        return regel instanceof ExtLogRecord ext ? ext.getFormattedMessage() : regel.getMessage();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(oorspronkelijkNiveau);
    }
}
