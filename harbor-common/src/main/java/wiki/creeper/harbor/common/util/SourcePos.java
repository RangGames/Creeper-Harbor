package wiki.creeper.harbor.common.util;

/** Position within a file. Lines and columns count from 1. */
public record SourcePos(String file, int line, int column) {

    @Override
    public String toString() {
        return file + " " + line + "행 " + column + "열";
    }
}
