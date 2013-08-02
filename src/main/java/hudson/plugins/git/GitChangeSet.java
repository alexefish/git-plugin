package hudson.plugins.git;

import hudson.MarkupText;
import hudson.model.Hudson;
import hudson.model.User;
import hudson.plugins.git.GitSCM.DescriptorImpl;
import hudson.scm.ChangeLogAnnotator;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.EditType;
import hudson.tasks.Mailer;
import hudson.tasks.Mailer.UserProperty;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.fixEmpty;

/**
 * Represents a change set.
 * @author Nigel Magnay
 */
public class GitChangeSet extends ChangeLogSet.Entry {

    private static final String PREFIX_COMMITTER = "committer ";
    private static final String IDENTITY = "([^<]*)<(.*)> (.*) (.*)";//starts with everything-but
    private static final String PREFIX_BRANCH = "Changes in branch ";
    private static final String BRANCH_PATTERN = "([-_a-zA-Z0-9/]*),";


    private static final Pattern FILE_LOG_ENTRY = Pattern.compile("^:[0-9]{6} [0-9]{6} ([0-9a-f]{40}) ([0-9a-f]{40}) ([ACDMRTUX])(?>[0-9]+)?\t(.*)$");
    private static final Pattern AUTHOR_ENTRY = Pattern.compile("Author: (.*) <(.*)>");
    private static final Pattern DATE_ENTRY = Pattern.compile("Date: (.*) (.*)");
    private static final Pattern COMMITTER_ENTRY = Pattern.compile("^"
            + PREFIX_COMMITTER + IDENTITY + "$");
    private static final Pattern RENAME_SPLIT = Pattern.compile("^(.*?)\t(.*)$");
    private static final Pattern BRANCH_ENTRY = Pattern.compile("^"
            + PREFIX_BRANCH + BRANCH_PATTERN + " .*$");
    private static final Pattern INSERTIONS = Pattern.compile("([0-9]+(?:\\.[0-9]*)?) insertions?");
    private static final Pattern DELETIONS = Pattern.compile("([0-9]+(?:\\.[0-9]*)?) deletions?");
    private static final Pattern CHANGED = Pattern.compile("([0-9]+(?:\\.[0-9]*)?) files? changed");

    private static final String NULL_HASH = "0000000000000000000000000000000000000000";
    private String  branch;
    private String  committer;
    private String  committerEmail;
    private String  committerTime;
    private String  committerTz;
    private String  author;
    private String  authorEmail;
    private String  authorTime;
    private String  authorTz;
    private String  comment;
    private String  title;
    private Integer filesChanged;
    private Integer insertions;
    private Integer deletions;
    private String  id;
    private String  parentCommit;
    private Collection<Path> paths = new HashSet<Path>();
    private boolean authorOrCommitter;

    /**
     * Create Git change set using information in given lines
     *
     * @param lines
     * @param authorOrCommitter
     */
    public GitChangeSet(List<String> lines, boolean authorOrCommitter) {
        this.authorOrCommitter = authorOrCommitter;
        if (lines.size() > 0) {
            parseCommit(lines);
        }
        else
        {
            throw new RuntimeException("No lines " + authorOrCommitter);
        }
    }

    private void parseCommit(List<String> lines) {

        StringBuilder message = new StringBuilder();

        for (String line : lines) {
            if( line.length() < 1)
                continue;
            if (line.startsWith("commit ")) {
                this.id = line.split(" ")[1];
            } else if (line.startsWith("tree ")) {
            } else if (line.startsWith("parent ")) {
                this.parentCommit = line.split(" ")[1];
            } else if (line.startsWith(PREFIX_BRANCH)) {
                Matcher branchMatcher = BRANCH_ENTRY.matcher(line);
                if (branchMatcher.matches()
                        && branchMatcher.groupCount() >= 1) {
                    this.branch = branchMatcher.group(1).trim();
                }
            } else if (line.startsWith("Author")) {
                Matcher authorMatcher = AUTHOR_ENTRY.matcher(line);
                if (authorMatcher.matches()) {
                    this.author = authorMatcher.group(1).trim();
                    this.authorEmail = authorMatcher.group(2);
                    this.committer = authorMatcher.group(1).trim();
                    this.committerEmail = authorMatcher.group(2);
                }
            } else if(line.startsWith("Date")) {
                Matcher dateMatcher = DATE_ENTRY.matcher(line);
                if (dateMatcher.matches()) {
                    this.authorTime = dateMatcher.group(1).trim();
                    this.authorTz = dateMatcher.group(2);
                    this.committerTime = dateMatcher.group(1).trim();
                    this.committerTz = dateMatcher.group(2);
                }
            } else if (line.contains("changed")){
                Matcher insertionsMatcher = INSERTIONS.matcher(line);
                if(insertionsMatcher.find())
                {
                    this.insertions = Integer.parseInt(insertionsMatcher.group(1));
                }
                Matcher deletionsMatcher = DELETIONS.matcher(line);
                if(deletionsMatcher.find())
                {
                    this.deletions = Integer.parseInt(deletionsMatcher.group(1));
                }
                Matcher filesChangedMatcher = CHANGED.matcher(line);
                if(filesChangedMatcher.find())
                {
                    this.filesChanged = Integer.parseInt(filesChangedMatcher.group(1));
                }
            } else if (line.startsWith("    ")) {
                message.append(line.substring(4)).append('\n');
            } else if (':' == line.charAt(0)) {
                Matcher fileMatcher = FILE_LOG_ENTRY.matcher(line);
                if (fileMatcher.matches() && fileMatcher.groupCount() >= 4) {
                    String mode = fileMatcher.group(3);
                    if (mode.length() == 1) {
                        String src = null;
                        String dst = null;
                        String path = fileMatcher.group(4);
                        char editMode = mode.charAt(0);
                        if (editMode == 'M' || editMode == 'A' || editMode == 'D'
                            || editMode == 'R' || editMode == 'C') {
                            src = parseHash(fileMatcher.group(1));
                            dst = parseHash(fileMatcher.group(2));
                        }

                        // Handle rename as two operations - a delete and an add
                        if (editMode == 'R') {
                            Matcher renameSplitMatcher = RENAME_SPLIT.matcher(path);
                            if (renameSplitMatcher.matches() && renameSplitMatcher.groupCount() >= 2) {
                                String oldPath = renameSplitMatcher.group(1);
                                String newPath = renameSplitMatcher.group(2);
                                this.paths.add(new Path(src, dst, 'D', oldPath, this));
                                this.paths.add(new Path(src, dst, 'A', newPath, this));
                            }
                        }
                        // Handle copy as an add
                        else if (editMode == 'C') {
                            Matcher copySplitMatcher = RENAME_SPLIT.matcher(path);
                            if (copySplitMatcher.matches() && copySplitMatcher.groupCount() >= 2) {
                                String newPath = copySplitMatcher.group(2);
                                this.paths.add(new Path(src, dst, 'A', newPath, this));
                            }
                        }
                        else {
                            this.paths.add(new Path(src, dst, editMode, path, this));
                        }
                    }
                }
            }
        }

        this.comment = message.toString();

        int endOfFirstLine = this.comment.indexOf('\n');
        if (endOfFirstLine == -1) {
            this.title = this.comment;
        } else {
            this.title = this.comment.substring(0, endOfFirstLine);
        }
    }

    private String parseHash(String hash) {
        return NULL_HASH.equals(hash) ? null : hash;
    }

    @Exported
    public String getDate() {
        DateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String dateStr;
        String csTime;
        String csTz;
        Date csDate;

        if (authorOrCommitter) {
            csTime = this.authorTime;
            csTz = this.authorTz;
        }
        else {
            csTime = this.committerTime;
            csTz = this.committerTz;
        }

        try {
            csDate = new Date(Long.parseLong(csTime) * 1000L);
        } catch (NumberFormatException e) {
            csDate = new Date();
        }

        dateStr = fmt.format(csDate) + " " + csTz;

        return dateStr;
    }

    @Override
    public long getTimestamp() {
        return 0;
    }

    @Override
    public String getCommitId() {
        return id;
    }

    @Override
    public void setParent(ChangeLogSet parent) {
        super.setParent(parent);
    }

    public String getParentCommit() {
        return parentCommit;
    }


    @Override
    public Collection<String> getAffectedPaths() {
        Collection<String> affectedPaths = new HashSet<String>(this.paths.size());
        for (Path file : this.paths) {
            affectedPaths.add(file.getPath());
        }
        return affectedPaths;
    }

    /**
     * Gets the files that are changed in this commit.
     * @return
     *      can be empty but never null.
     */
    @Exported
    public Collection<Path> getPaths() {
        return paths;
    }

    @Override
    public Collection<Path> getAffectedFiles() {
        return this.paths;
    }

    /**
     * Returns user of the change set.
     *
     * @param csAuthor user name.
     * @param csAuthorEmail user email.
     * @param createAccountBasedOnEmail true if create new user based on committer's email.
     * @return {@link User}
     */
    public User findOrCreateUser(String csAuthor, String csAuthorEmail, boolean createAccountBasedOnEmail) {
        User user;
        if (createAccountBasedOnEmail) {
            user = User.get(csAuthorEmail, false);

            if (user == null) {
                try {
                    user = User.get(csAuthorEmail, true);
                    user.setFullName(csAuthor);
                    user.addProperty(new Mailer.UserProperty(csAuthorEmail));
                    user.save();
                } catch (IOException e) {
                    // add logging statement?
                }
            }
        } else {
            user = User.get(csAuthor, false);

            if (user == null)
                user = User.get(csAuthorEmail.split("@")[0], true);
        }
        // set email address for user if none is already available
        if (fixEmpty(csAuthorEmail) != null && !isMailerPropertySet(user)) {
            try {
                user.addProperty(new Mailer.UserProperty(csAuthorEmail));
            } catch (IOException e) {
                // ignore error
            }
        }
        return user;
    }

	private boolean isMailerPropertySet(User user) {
		UserProperty property = user.getProperty(Mailer.UserProperty.class);
		return property != null
            && property.hasExplicitlyConfiguredAddress();
	}

	private boolean isCreateAccountBasedOnEmail() {
        DescriptorImpl descriptor = (DescriptorImpl) Hudson.getInstance().getDescriptor(GitSCM.class);

        return descriptor.isCreateAccountBasedOnEmail();
    }

    @Override
    @Exported
    public User getAuthor() {
        String csAuthor;
        String csAuthorEmail;

        // If true, use the author field from git log rather than the committer.
        if (authorOrCommitter)
        {
            csAuthor = this.author;
            csAuthorEmail = this.authorEmail;
        }
        else
        {
            csAuthor = this.committer;
            if(this.committerEmail != null) {
                csAuthorEmail = this.committerEmail;
            } else {
                csAuthorEmail = "unknown@ustwo.co.uk";
            }
        }

        if (csAuthor == null) {
            csAuthor = "Unknown";
        }

        return findOrCreateUser(csAuthor, csAuthorEmail, isCreateAccountBasedOnEmail());
    }

    /**
     * Gets the author name for this changeset - note that this is mainly here
     * so that we can test authorOrCommitter without needing a fully instantiated
     * Hudson (which is needed for User.get in getAuthor()).
     *
     * @return author name
     */
    public String getAuthorName() {
        // If true, use the author field from git log rather than the committer.
        String csAuthor = authorOrCommitter ? author : committer;
        if (csAuthor == null)
            csAuthor = "Unknown";
        return csAuthor;
    }

    @Override
    @Exported
    public String getMsg() {
        return this.title;
    }

    @Exported
    public Integer getInsertions() {
        if(this.insertions == null)
        {
            this.insertions = 0;
        }
        return this.insertions;
    }

    @Exported
    public Integer getFilesChanged() {
        if(this.filesChanged == null)
        {
            this.filesChanged = 0;
        }
        return this.filesChanged;
    }

    @Exported
    public Integer getDeletions() {
        if(this.deletions == null)
        {
            this.deletions = 0;
        }
        return this.deletions;
    }

    @Exported
    public String getId() {
        return this.id;
    }

    public String getRevision() {
        return this.id;
    }

    @Exported
    public String getComment() {
        return this.comment;
    }

    /**
     * Gets {@linkplain #getComment() the comment} fully marked up by {@link ChangeLogAnnotator}.
     */
    public String getCommentAnnotated() {
        MarkupText markup = new MarkupText(getComment());
        for (ChangeLogAnnotator a : ChangeLogAnnotator.all())
            a.annotate(getParent().build,this,markup);

        return markup.toString(false);
    }

    public String getBranch() {
        return this.branch;
    }

    @ExportedBean(defaultVisibility=999)
    public static class Path implements AffectedFile {

        private String src;
        private String dst;
        private char action;
        private String path;
        private GitChangeSet changeSet;

        private Path(String source, String destination, char action, String filePath, GitChangeSet changeSet) {
            this.src = source;
            this.dst = destination;
            this.action = action;
            this.path = filePath;
            this.changeSet = changeSet;
        }

        public String getSrc() {
            return src;
        }

        public String getDst() {
            return dst;
        }

        @Exported(name="file")
        public String getPath() {
            return path;
        }

        public GitChangeSet getChangeSet() {
            return changeSet;
        }

        @Exported
        public EditType getEditType() {
            switch (action) {
            case 'A':
                return EditType.ADD;
            case 'D':
                return EditType.DELETE;
            default:
                return EditType.EDIT;
            }
        }
    }

    public int hashCode() {
        return id != null ? id.hashCode() : super.hashCode();
    }

    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (obj instanceof GitChangeSet)
            return id != null && id.equals(((GitChangeSet) obj).id);
        return false;
    }

    private static final Logger LOGGER = Logger.getLogger(GitChangeSet.class.getName());
}
