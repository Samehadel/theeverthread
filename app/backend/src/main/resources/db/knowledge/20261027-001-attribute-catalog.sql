--liquibase formatted sql
--changeset theeverthread:20261027-001-attribute-catalog
CREATE TABLE KNOWLEDGE_ATTRIBUTE (
    ID VARCHAR(100) PRIMARY KEY CHECK (LENGTH(TRIM(ID)) > 0),
    DISPLAY_LABEL VARCHAR(200) NOT NULL CHECK (LENGTH(TRIM(DISPLAY_LABEL)) > 0),
    DESCRIPTION TEXT NOT NULL CHECK (LENGTH(TRIM(DESCRIPTION)) > 0),
    SORT_ORDER INTEGER NOT NULL,
    ACTIVE BOOLEAN NOT NULL DEFAULT TRUE
);

-- Keep legacy classifier versions; new results include a catalog fingerprint.
ALTER TABLE KNOWLEDGE_TICKET ALTER COLUMN CLASSIFICATION_VERSION TYPE VARCHAR(100);

INSERT INTO KNOWLEDGE_ATTRIBUTE (ID, DISPLAY_LABEL, DESCRIPTION, SORT_ORDER) VALUES
    ('type:knowledge', 'Knowledge', 'Reusable information or guidance; do not add to every capture by default.', 0),
    ('type:coding', 'Coding', 'Implementing or explaining software code, not simply mentioning an app.', 1),
    ('type:problem', 'Problem', 'An obstacle or issue being investigated or solved in any part of life.', 2),
    ('type:decision', 'Decision', 'A choice made with its reasoning or tradeoffs, personal or professional.', 3),
    ('type:accomplishment', 'Accomplishment', 'A completed achievement or milestone, personal or professional.', 4),
    ('type:learning', 'Learning', 'An explicitly learned lesson, insight, or newly acquired skill.', 5),
    ('type:reflection', 'Reflection', 'Thoughts about experiences, feelings, values, or their meaning.', 6),
    ('type:experience', 'Experience', 'An event or lived experience being recorded.', 7),
    ('type:idea', 'Idea', 'A proposed possibility or creative thought not yet a decision.', 8),
    ('type:goal', 'Goal', 'An intended future outcome or commitment, not an already completed achievement.', 9),
    ('area:relationships', 'Relationships', 'Friendships, social connections, or interpersonal relationships. Example: I learned to listen before offering advice to a friend.', 10),
    ('area:family', 'Family', 'Parenting, relatives, or family life. Example: Sunday breakfast with my daughter, recording our time together.', 11),
    ('area:wellbeing', 'Wellbeing', 'Emotional wellbeing, rest, balance, or self-care; never infer a diagnosis. Example: My morning walk helps me unwind.', 12),
    ('area:health', 'Health', 'Explicit physical health, exercise, sleep, or nutrition; never infer a diagnosis.', 13),
    ('area:personal-growth', 'Personal Growth', 'Self-understanding, values, habits, or personal development.', 14),
    ('area:everyday-life', 'Everyday Life', 'Everyday activities, home, routines, or life events without a more specific topic. Example: I bought a new phone and set it up.', 15),
    ('area:finance', 'Finance', 'Personal or professional budgeting, saving, spending, or financial planning.', 16),
    ('area:career', 'Career', 'Career direction, job search, professional development, or work-life choices. Example: I decided to reduce overtime to spend evenings with my children.', 17),
    ('area:education', 'Education', 'Study, teaching, courses, or formal and informal education.', 18),
    ('area:creativity', 'Creativity', 'Art, writing, music, crafts, or other creative practice.', 19),
    ('area:travel', 'Travel', 'Journeys, places visited, or travel experiences and plans. Example: Watching spring flowers on holiday in Java.', 20),
    ('area:software', 'Software', 'Software engineering topics without a more specific technical area.', 21),
    ('area:backend', 'Backend', 'Server-side software implementation or behavior. Example: I fixed a Spring transaction retry bug on the server.', 22),
    ('area:frontend', 'Frontend', 'Client-side software or user-interface implementation.', 23),
    ('area:database', 'Database', 'Data storage, queries, schemas, or database operation.', 24),
    ('area:infrastructure', 'Infrastructure', 'Computing infrastructure, deployment, networking, or runtime operations.', 25),
    ('area:security', 'Security', 'Software or information security, authentication, or authorization.', 26),
    ('area:testing', 'Testing', 'Software testing or quality assurance, not exams or personal challenges.', 27),
    ('area:product', 'Product', 'Building or managing a product: user needs, product strategy, features, or roadmap. Not buying or using a product, personal goals, or generic ideas. Example: We prioritized onboarding improvements after user interviews.', 28),
    ('area:process', 'Process', 'An explicit organizational or team workflow or operational improvement. Not personal routines, habits, or any sequence of steps. Example: Our team reduced release handoffs.', 29),
    ('technology:java', 'Java', 'Java is explicitly used or discussed as a technology; incidental names are insufficient.', 30),
    ('technology:spring', 'Spring', 'Spring framework is explicitly used or discussed as a technology; incidental names are insufficient.', 31),
    ('technology:angular', 'Angular', 'Angular framework is explicitly used or discussed as a technology; incidental names are insufficient.', 32),
    ('technology:typescript', 'TypeScript', 'TypeScript is explicitly used or discussed as a technology; incidental names are insufficient.', 33),
    ('technology:javascript', 'JavaScript', 'JavaScript is explicitly used or discussed as a technology; incidental names are insufficient.', 34),
    ('technology:python', 'Python', 'Python programming language is explicitly used or discussed as a technology; incidental names are insufficient.', 35),
    ('technology:sql', 'SQL', 'SQL is explicitly used or discussed as a technology; incidental names are insufficient.', 36),
    ('technology:postgresql', 'PostgreSQL', 'PostgreSQL is explicitly used or discussed as a technology; incidental names are insufficient.', 37),
    ('technology:docker', 'Docker', 'Docker is explicitly used or discussed as a technology; incidental names are insufficient.', 38),
    ('technology:other', 'Other technology', 'An explicitly discussed software technology absent from this catalog. Never a fallback for nontechnical content.', 39);

--rollback DROP TABLE KNOWLEDGE_ATTRIBUTE;
-- The widened version column remains compatible with the earlier backend.
