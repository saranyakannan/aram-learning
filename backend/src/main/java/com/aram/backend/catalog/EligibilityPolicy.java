package com.aram.backend.catalog;

import com.aram.backend.config.AramProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The one shared eligibility rule (spec section 4): relevance ranking never decides it. A source can be offered to,
 * or approved for, a child only when it is part of the live catalog, its latest assessment cleared it, and the child's
 * age is inside the assessed range. Missing age information means "not eligible", never "any age".
 *
 * <p>One deliberate stretch (decision record 0010): for a child aged 4 (48-59 months) a source whose assessed minimum age is
 * up to 12 months higher is still eligible. It applies to the lower bound only, never to the upper bound, to other ages, or to
 * the safety checks.
 */
@Component
public class EligibilityPolicy {

	private final int stretchMonths;
	private final int stretchFromChildMonths;
	private final int stretchToChildMonths;

	/** The default stretch: one year for 4-year-olds. */
	public EligibilityPolicy() {
		this(12, 48, 59);
	}

	@Autowired
	public EligibilityPolicy(AramProperties properties) {
		this(properties.getEligibility().getAgeStretchMonths(), properties.getEligibility().getStretchFromChildMonths(),
				properties.getEligibility().getStretchToChildMonths());
	}

	public EligibilityPolicy(int stretchMonths, int stretchFromChildMonths, int stretchToChildMonths) {
		this.stretchMonths = Math.max(0, stretchMonths);
		this.stretchFromChildMonths = stretchFromChildMonths;
		this.stretchToChildMonths = stretchToChildMonths;
	}

	/** Is the child's age inside the source's assessed range, with no stretch? (Sources aimed a little older are not "in range".) */
	public boolean withinRange(ContentSource source, int childAgeMonths) {
		return source.minAgeMonths() != null && source.maxAgeMonths() != null
				&& childAgeMonths >= source.minAgeMonths() && childAgeMonths <= source.maxAgeMonths();
	}

	private int stretchFor(int childAgeMonths) {
		return childAgeMonths >= stretchFromChildMonths && childAgeMonths <= stretchToChildMonths ? stretchMonths : 0;
	}

	/** {@code reason} is safe to show a parent when {@code eligible} is false. */
	public record Decision(boolean eligible, String reason) {
		static Decision yes() {
			return new Decision(true, null);
		}

		static Decision no(String reason) {
			return new Decision(false, reason);
		}
	}

	/** May this source be approved for a child of this age? */
	public Decision forChild(ContentSource source, int childAgeMonths) {
		if (source.catalogStatus() != Statuses.Catalog.ACTIVE) {
			return Decision.no("That channel isn't clearly safe for your child, so I can't add it.");
		}
		if (source.assessmentStatus() != Statuses.Assessment.ASSESSED
				&& source.assessmentStatus() != Statuses.Assessment.APPROVED_FOR_RECOMMENDATION) {
			return Decision.no("That channel isn't clearly safe for your child, so I can't add it.");
		}
		if (source.minAgeMonths() == null || source.maxAgeMonths() == null) {
			return Decision.no("I don't know which ages that channel suits, so I can't add it.");
		}
		if (childAgeMonths < source.minAgeMonths() - stretchFor(childAgeMonths) || childAgeMonths > source.maxAgeMonths()) {
			return Decision.no("That channel is meant for a different age than your child's, so I can't add it.");
		}
		return Decision.yes();
	}

	/** Sources shown to other parents need a human-reviewed assessment on top of {@link #forChild}. */
	public boolean recommendable(ContentSource source, int childAgeMonths) {
		return source.assessmentStatus() == Statuses.Assessment.APPROVED_FOR_RECOMMENDATION
				&& forChild(source, childAgeMonths).eligible();
	}

	/** Is an already-approved source still fine to play? Age is not re-checked: the parent approved it for their child. */
	public boolean stillPlayable(ContentSource source) {
		return source.catalogStatus() == Statuses.Catalog.ACTIVE
				&& (source.assessmentStatus() == Statuses.Assessment.ASSESSED
				|| source.assessmentStatus() == Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
	}
}
