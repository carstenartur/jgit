/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.search;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

/**
 * A Lucene {@link TokenFilter} that processes tokens emitted by
 * {@link EcjTokenizer}.
 * <p>
 * For identifiers, it applies CamelCase splitting, emitting the sub-tokens at
 * the same position (via {@link PositionIncrementAttribute} set to 0). For
 * string literals, it strips surrounding quotes and indexes the content. For
 * operators, the tokens are skipped (no search value). Keywords pass through
 * unchanged. Comments are optionally indexed or skipped.
 * </p>
 */
public final class EcjTokenFilter extends TokenFilter {

	private final CharTermAttribute termAttr = addAttribute(
			CharTermAttribute.class);

	private final TypeAttribute typeAttr = addAttribute(TypeAttribute.class);

	private final PositionIncrementAttribute posIncAttr = addAttribute(
			PositionIncrementAttribute.class);

	private final boolean indexComments;

	private String[] pendingParts;

	private int pendingIndex;

	private String savedType;

	/**
	 * Create a new ECJ token filter that skips comments.
	 *
	 * @param input
	 *            the upstream token stream
	 */
	public EcjTokenFilter(TokenStream input) {
		this(input, false);
	}

	/**
	 * Create a new ECJ token filter.
	 *
	 * @param input
	 *            the upstream token stream
	 * @param indexComments
	 *            whether to index comment tokens
	 */
	public EcjTokenFilter(TokenStream input, boolean indexComments) {
		super(input);
		this.indexComments = indexComments;
	}

	@Override
	public boolean incrementToken() throws IOException {
		// Emit pending CamelCase sub-parts
		if (pendingParts != null && pendingIndex < pendingParts.length) {
			clearAttributes();
			termAttr.setEmpty().append(pendingParts[pendingIndex]);
			typeAttr.setType(savedType);
			posIncAttr.setPositionIncrement(0);
			pendingIndex++;
			if (pendingIndex >= pendingParts.length) {
				pendingParts = null;
			}
			return true;
		}
		pendingParts = null;

		while (input.incrementToken()) {
			String type = typeAttr.type();

			// Skip operators
			if (EcjTokenizer.TYPE_OPERATOR.equals(type)) {
				continue;
			}

			// Skip comments unless configured to index them
			if (EcjTokenizer.TYPE_COMMENT.equals(type) && !indexComments) {
				continue;
			}

			// Skip annotations (the '@' symbol itself)
			if (EcjTokenizer.TYPE_ANNOTATION.equals(type)) {
				continue;
			}

			// For identifiers: apply CamelCase splitting
			if (EcjTokenizer.TYPE_IDENTIFIER.equals(type)) {
				String term = termAttr.toString();
				String[] parts = splitCamelCase(term);
				if (parts.length > 1) {
					// The original term is already emitted
					// Queue sub-parts at position increment 0
					savedType = type;
					pendingParts = parts;
					pendingIndex = 0;
				}
				return true;
			}

			// Keywords, string literals, number literals: pass through
			return true;
		}
		return false;
	}

	@Override
	public void reset() throws IOException {
		super.reset();
		pendingParts = null;
		pendingIndex = 0;
	}

	static String[] splitCamelCase(String identifier) {
		if (identifier == null || identifier.isEmpty()) {
			return new String[0];
		}
		java.util.List<String> parts = new java.util.ArrayList<>();
		int start = 0;
		for (int i = 1; i < identifier.length(); i++) {
			if (Character.isUpperCase(identifier.charAt(i))
					&& Character.isLowerCase(identifier.charAt(i - 1))) {
				parts.add(identifier.substring(start, i));
				start = i;
			}
		}
		parts.add(identifier.substring(start));
		return parts.toArray(new String[0]);
	}
}
