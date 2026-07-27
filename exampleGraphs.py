import matplotlib.pyplot as plt
import numpy as np
import seaborn as sns

sns.set_theme(style="whitegrid")

# Repositories evaluated
repos = ["Repo A", "Repo B", "Repo C", "Repo D", "Repo E"]

# Breakdown of total resolved dependencies per repo
perfect_matches = np.array([45, 32, 60, 22, 51])
version_mismatches = np.array([0, 2, 4, 1, 0])
scope_mismatches = np.array([0, 1, 0, 2, 1])

# Plotting the structural accuracy breakdown
fig, ax = plt.subplots(figsize=(7, 5))

# Stack the bars
ax.bar(repos, perfect_matches, label="Perfect Match (True Positive)", color="#55a868")
ax.bar(
    repos,
    version_mismatches,
    bottom=perfect_matches,
    label="Version Mismatch (Highest vs Nearest)",
    color="#c44e52",
)
ax.bar(
    repos,
    scope_mismatches,
    bottom=perfect_matches + version_mismatches,
    label="Scope Mismatch",
    color="#ccb974",
)

ax.set_ylabel("Number of Resolved Dependencies", fontweight="bold")
ax.set_xlabel("Historical Repositories", fontweight="bold")
ax.set_title("RQ1 Evaluation: Structural Tree Correctness Breakdown")
ax.legend(loc="upper left")

plt.tight_layout()
plt.savefig("rq1_tree_discrepancies.png", dpi=300)
plt.close()