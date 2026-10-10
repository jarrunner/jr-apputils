package jarrunner.updateutils;

/** What a check or an update came to, as jr reported it (-Xjr:batch).
 *  @param status   CURRENT, NEWER (check only), UPDATED, ERROR, or UNAVAILABLE when the app is not running under jr
 *  @param version  this build's app.version, or null
 *  @param latest   the newest version on the app's channel when there is a newer one, or null
 *  @param message  one line or a few, ready to show the user
 *  @param exitCode jr's exit code (update-check: 0 current, 10 newer, 1 error; update: 0 updated or current, 1 error) */
public record UpdateResult(Status status, String version, String latest, String message, int exitCode) {
    public enum Status { CURRENT, NEWER, UPDATED, ERROR, UNAVAILABLE }

    public boolean ok() {
        return status != Status.ERROR && status != Status.UNAVAILABLE;
    }

    /** True when a new version was put in place: it runs from the next launch. */
    public boolean updated() {
        return status == Status.UPDATED;
    }
}
