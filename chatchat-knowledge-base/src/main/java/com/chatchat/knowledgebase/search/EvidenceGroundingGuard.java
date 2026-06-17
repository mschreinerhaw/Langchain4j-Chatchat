package com.chatchat.knowledgebase.search;

/**
 * @deprecated Use {@link AnswerGroundingGuard}. Kept only for source compatibility.
 */
@Deprecated(forRemoval = false)
public class EvidenceGroundingGuard extends AnswerGroundingGuard {

    public EvidenceGroundingGuard(SearchTokenizer tokenizer, EvidenceContextFormatter formatter) {
        super(tokenizer, formatter);
    }
}
