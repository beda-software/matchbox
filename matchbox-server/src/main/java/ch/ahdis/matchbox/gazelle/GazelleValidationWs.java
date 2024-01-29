package ch.ahdis.matchbox.gazelle;

import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.util.StopWatch;
import ch.ahdis.fhir.hapi.jpa.validation.ValidationProvider;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.MatchboxEngineSupport;
import ch.ahdis.matchbox.StructureDefinitionResourceProvider;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.engine.exception.MatchboxEngineCreationException;
import ch.ahdis.matchbox.gazelle.models.metadata.Interface;
import ch.ahdis.matchbox.gazelle.models.metadata.RestBinding;
import ch.ahdis.matchbox.gazelle.models.metadata.Service;
import ch.ahdis.matchbox.gazelle.models.validation.*;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_40_50;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.r5.model.Duration;
import org.hl7.fhir.r5.model.StringType;
import org.hl7.fhir.r5.model.UriType;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The WebService for validation with the new Gazelle Validation API.
 *
 * @author Quentin Ligier
 **/
@RestController
@RequestMapping(path = "/gazelle")
public class GazelleValidationWs {
	private static final Logger log = LoggerFactory.getLogger(GazelleValidationWs.class);

	/**
	 * HTTP paths.
	 */
	private static final String METADATA_PATH = "/metadata";
	private static final String PROFILES_PATH = "/validation/profiles";
	private static final String VALIDATE_PATH = "/validation/validate";

	private final MatchboxEngineSupport matchboxEngineSupport;

	private final StructureDefinitionResourceProvider structureDefinitionProvider;

	private final CliContext cliContext;

	public GazelleValidationWs(final MatchboxEngineSupport matchboxEngineSupport,
										final CliContext cliContext,
										final StructureDefinitionResourceProvider structureDefinitionProvider) {
		this.matchboxEngineSupport = Objects.requireNonNull(matchboxEngineSupport);
		this.cliContext = Objects.requireNonNull(cliContext);
		this.structureDefinitionProvider = Objects.requireNonNull(structureDefinitionProvider);
	}

	/**
	 * Returns the metadata of the validation service.
	 */
	@GetMapping(path = METADATA_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public Service getMetadata(final HttpServletRequest request) {
		final var service = new Service();
		service.setName("Matchbox");
		service.setVersion(VersionUtil.getVersion());
		service.setInstanceId("NOT_SET");
		service.setReplicaId("NOT_SET");

		final var theInterface = new Interface();
		theInterface.setType("validationInterface");
		theInterface.setInterfaceName("ValidationInterface");
		theInterface.setInterfaceVersion("1.0.0");
		theInterface.setRequired(true);

		final var binding = new RestBinding();
		//binding.setServiceUrl(request.getRequestURL().toString().replace(METADATA_PATH, VALIDATE_PATH));
		binding.setServiceUrl("https://3mb7wd5k-8080.euw.devtunnels.ms/matchboxv3/gazelle/validation/validate");
		binding.setType("restBinding");

		theInterface.setValidationProfiles(this.getProfiles());

		theInterface.addBinding(binding);
		service.setProvidedInterfaces(List.of(theInterface));

		return service;
	}

	/**
	 * Returns the list of profiles supported by this server.
	 */
	@GetMapping(path = PROFILES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ValidationProfile> getProfiles() {
		return this.structureDefinitionProvider.getCurrentPackageResources().stream()
			.map(packageVersionResource -> {
				final var profile = new ValidationProfile();
				profile.setProfileID(packageVersionResource.getCanonicalUrl());
				// PATCHed: filename contains the StructureDefinition title.
				profile.setProfileName(packageVersionResource.getFilename());
				profile.setDomain(packageVersionResource.getPackageVersion().getPackageId());
				return profile;
			}).collect(Collectors.toList());
	}

	/**
	 * Performs the validation of the given items with the given profile.
	 */
	@PostMapping(path = VALIDATE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces =
		MediaType.APPLICATION_JSON_VALUE)
	public ValidationReport postValidate(@RequestBody final ValidationRequest validationRequest) {
		final var sw = new StopWatch();
		sw.startTask("Total");

		final var report = new ValidationReport();
		report.setValidationItems(new ArrayList<>(validationRequest.getValidationItems().size()));
		report.setReports(new ArrayList<>(validationRequest.getValidationItems().size()));
		report.setDisclaimer("Matchbox disclaims");

		// Get the Matchbox engine for the requested profile
		final MatchboxEngine engine;
		try {
			engine = this.getEngine(validationRequest.getValidationProfileId());
		} catch (final Exception exception) {
			report.addValidationSubReport(unexpectedError(exception.getMessage()));
			return update(report);
		}
		final StructureDefinition structDef = engine.getStructureDefinition(validationRequest.getValidationProfileId());

		// Add the validation method
		final var method = new ValidationMethod();
		method.setValidationProfileID(validationRequest.getValidationProfileId());
		method.setValidationServiceName("Matchbox");
		method.setValidationServiceVersion(VersionUtil.getVersion());
		report.setValidationMethod(method);

		// Add validation info
		report.setAdditionalMetadata(new ArrayList<>(this.cliContext.getValidateEngineParameters().size() + engine.getContext().getLoadedPackages().size() + 6));
		final var sessionId = this.matchboxEngineSupport.getSessionId(engine);
		if (sessionId != null) {
			report.addAdditionalMetadata(new Metadata().setName("sessionId").setValue(sessionId));
		}
		report.addAdditionalMetadata(new Metadata().setName("validatorVersion").setValue(VersionUtil.getPoweredBy()));
		for (final var pkg : engine.getContext().getLoadedPackages()) {
			report.addAdditionalMetadata(new Metadata().setName("package").setValue(pkg));
		}
		report.addAdditionalMetadata(new Metadata().setName("profile").setValue(structDef.getUrl()));
		report.addAdditionalMetadata(new Metadata().setName("profileVersion").setValue(structDef.getVersion()));
		report.addAdditionalMetadata(new Metadata().setName("profileDate").setValue(structDef.getDateElement().getValueAsString()));

		// Add the validation parameters as additional metadata
		for (final Field field : this.cliContext.getValidateEngineParameters()) {
			field.setAccessible(true);
			final var metadata = new Metadata();
			metadata.setName(field.getName());
			try {
				metadata.setValue(String.valueOf(field.get(this.cliContext)));
			} catch (final IllegalAccessException exception) {
				continue;
			}
			report.addAdditionalMetadata(metadata);
		}

		// Add the validation items (requests) to the response
		report.getValidationItems().addAll(validationRequest.getValidationItems());

		// Perform the validation of all items with the given engine
		for (final var item : validationRequest.getValidationItems()) {
			try {
				report.addValidationSubReport(this.validateItem(engine, item, validationRequest.getValidationProfileId()));
			} catch (final Exception exception) {
				report.addValidationSubReport(unexpectedError(exception.getMessage()));
			}
		}

		sw.endCurrentTask();
		report.addAdditionalMetadata(new Metadata().setName("total").setValue(sw.getMillis() + "ms"));

		return update(report);
	}

	/**
	 * Retrieves the Matchbox engine for the given profile.
	 */
	MatchboxEngine getEngine(final String profile) {
		final MatchboxEngine engine;
		try {
			engine = this.matchboxEngineSupport.getMatchboxEngine(profile, cliContext, true, false);
		} catch (final Exception e) {
			log.error("Error while initializing the validation engine", e);
			throw new MatchboxEngineCreationException("Error while initializing the validation engine: %s".formatted(e.getMessage()));
		}
		if (engine == null || engine.getStructureDefinition(profile) == null) {
			throw new MatchboxEngineCreationException(
				"Validation for profile '%s' not supported by this validator instance".formatted(profile));
		}
		if (!this.matchboxEngineSupport.isInitialized()) {
			throw new RuntimeException("Validation engine not initialized, please try again");
		}
		return engine;
	}

	/**
	 * Performs the validation of the given item with the given engine.
	 */
	ValidationSubReport validateItem(final MatchboxEngine engine,
									 final ValidationItem item,
									 final String profile) {
		final String content = new String(item.getContent(), StandardCharsets.UTF_8);
		final var encoding = EncodingEnum.detectEncoding(content);

		final var subReport = new ValidationSubReport();
		subReport.setName("Validation of item #%s".formatted(item.getItemId()));
		try {
			final var messages = ValidationProvider.doValidate(engine, content, encoding, profile);
			messages.stream().map(this::convertMessageToReport).forEach(subReport::addAssertionReport);
		} catch (final Exception e) {
			log.error("Error during validation", e);
			subReport.addUnexpectedError(new UnexpectedError().setMessage("Error during validation: %s".formatted(e.getMessage())));
		}

		return subReport;
	}

	/**
	 * Converts a validation message (HAPI) to an assertion report (Gazelle).
	 */
	AssertionReport convertMessageToReport(final ValidationMessage message) {
		final var assertionReport = new AssertionReport();
		switch (message.getLevel()) {
			case FATAL, ERROR:
				assertionReport.setPriority(RequirementPriority.MANDATORY);
				assertionReport.setResult(ValidationTestResult.FAILED);
				assertionReport.setSeverity(SeverityLevel.ERROR);
				break;
			case WARNING:
				assertionReport.setPriority(RequirementPriority.RECOMMENDED);
				assertionReport.setResult(ValidationTestResult.FAILED);
				assertionReport.setSeverity(SeverityLevel.WARNING);
				break;
			case INFORMATION:
			default:
				assertionReport.setResult(ValidationTestResult.PASSED); // Can't use UNDEFINED here, because it weights
				// more than FAILED
				assertionReport.setSeverity(SeverityLevel.INFO);
				break;
		}

		// See AssertionReport#LINE_COL_PATT for the expected format
		assertionReport.setSubjectLocation("line %d, column %d, FHIRPath: %s".formatted(message.getLine(),
																											     message.getCol(),
																												  message.getLocation()));

		/*
		TODO: invId is only available in later core versions
		if (message.getInvId() != null) {
			assertionReport.setAssertionID(message.getInvId());
		} else */if (message.getMessageId() != null) {
			assertionReport.setAssertionID(message.getMessageId());
		}
		if (message.getSource() != null) {
			assertionReport.setAssertionType("%s (reported by the %s)".formatted(message.getType().getDisplay(),
																						            message.getSource().name()));
		} else {
			assertionReport.setAssertionType(message.getType().getDisplay());
		}

		// Description, with slice info if available
		var description = new StringBuilder();
		description.append(message.getMessage());
		if (message.sliceText != null && message.sliceText.length > 0) {
			description.append("<br/><br/>Slice information:<br/><ul>");
			for (final var slice : message.sliceText) {
				description.append("<li>").append(slice).append("</li>");
			}
			description.append("</ul>");
		}
		assertionReport.setDescription(description.toString());
		return assertionReport;
	}

	/**
	 * Creates a validation sub-report that only contains an unexpected error.
	 */
	static ValidationSubReport unexpectedError(final String message) {
		final var report = new ValidationSubReport();
		report.setName("Unexpected error");
		report.setSubReportResult(ValidationTestResult.FAILED);
		report.addUnexpectedError(new UnexpectedError().setMessage(message));
		report.getSubCounters().incrementUnexpectedErrors();
		return report;
	}

	/**
	 * Updates the counters and overall result of the given report.
	 */
	static ValidationReport update(final ValidationReport report) {
		report.getReports().forEach(ValidationSubReport::computeCountersSubReport);
		report.getReports().forEach(ValidationSubReport::computeResultSubReport);
		report.computeCounters();
		report.computeOverallResult();
		return report;
	}
}