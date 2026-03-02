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

import org.hibernate.search.backend.lucene.analysis.LuceneAnalysisConfigurationContext;
import org.hibernate.search.backend.lucene.analysis.LuceneAnalysisConfigurer;

/**
 * Configures Lucene analyzers tailored for Java source code search.
 * <p>
 * This configurer defines named analyzers for CamelCase-aware identifier
 * search, file path and package search, commit message analysis, and
 * dot-qualified fully qualified name search. It is registered with Hibernate
 * Search via the {@code hibernate.search.backend.analysis.configurer}
 * property.
 * </p>
 */
public class JavaSourceAnalysisConfigurer implements LuceneAnalysisConfigurer {

	@Override
	public void configure(LuceneAnalysisConfigurationContext context) {
		// generateWordParts + preserveOriginal: indexes both the original
		// identifier and its CamelCase parts (e.g. "StringBuilder" →
		// "StringBuilder", "String", "Builder")
		context.analyzer("javaIdentifier").custom() //$NON-NLS-1$
				.tokenizer("standard") //$NON-NLS-1$
				.tokenFilter("wordDelimiterGraph") //$NON-NLS-1$
				.param("splitOnCaseChange", "1") //$NON-NLS-1$ //$NON-NLS-2$
				.param("generateWordParts", "1") //$NON-NLS-1$ //$NON-NLS-2$
				.param("preserveOriginal", "1") //$NON-NLS-1$ //$NON-NLS-2$
				.tokenFilter("lowercase"); //$NON-NLS-1$

		context.analyzer("javaPath").custom() //$NON-NLS-1$
				.tokenizer("standard") //$NON-NLS-1$
				.tokenFilter("wordDelimiterGraph") //$NON-NLS-1$
				.param("splitOnCaseChange", "1") //$NON-NLS-1$ //$NON-NLS-2$
				.tokenFilter("lowercase"); //$NON-NLS-1$

		context.analyzer("commitMessage").custom() //$NON-NLS-1$
				.tokenizer("standard") //$NON-NLS-1$
				.tokenFilter("wordDelimiterGraph") //$NON-NLS-1$
				.param("splitOnCaseChange", "1") //$NON-NLS-1$ //$NON-NLS-2$
				.param("preserveOriginal", "1") //$NON-NLS-1$ //$NON-NLS-2$
				.tokenFilter("lowercase") //$NON-NLS-1$
				.tokenFilter("stop"); //$NON-NLS-1$

		context.analyzer("dotQualifiedName").custom() //$NON-NLS-1$
				.tokenizer("keyword") //$NON-NLS-1$
				.tokenFilter("patternReplace") //$NON-NLS-1$
				.param("pattern", "\\.") //$NON-NLS-1$ //$NON-NLS-2$
				.param("replacement", " ") //$NON-NLS-1$ //$NON-NLS-2$
				.tokenFilter("lowercase"); //$NON-NLS-1$
	}
}
