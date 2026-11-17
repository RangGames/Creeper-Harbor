package wiki.creeper.harbor.common.util;

/** 파일 안의 위치. 줄·열은 1부터 센다. */
public record SourcePos(String file, int line, int column) {

    @Override
    public String toString() {
        return file + " " + line + "행 " + column + "열";
    }
}
