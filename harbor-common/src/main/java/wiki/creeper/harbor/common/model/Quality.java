package wiki.creeper.harbor.common.model;

/** Quality ★1~★5. */
public record Quality(int stars) implements Comparable<Quality> {

    public static final int MIN = 1;
    public static final int MAX = 5;

    public Quality {
        if (stars < MIN || stars > MAX) {
            throw new IllegalArgumentException("품질은 1~5 사이여야 합니다: " + stars);
        }
    }

    public static Quality of(int stars) {
        return new Quality(stars);
    }

    public boolean isMax() {
        return stars == MAX;
    }

    /** ★★★☆☆ form. */
    public String display() {
        return "★".repeat(stars) + "☆".repeat(MAX - stars);
    }

    @Override
    public int compareTo(Quality other) {
        return Integer.compare(stars, other.stars);
    }
}
