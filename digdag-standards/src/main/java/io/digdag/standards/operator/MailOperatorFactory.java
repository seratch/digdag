package io.digdag.standards.operator;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigElement;
import io.digdag.client.config.ConfigException;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.SecretProvider;
import io.digdag.spi.OperatorContext;
import io.digdag.spi.SecretAccessList;
import io.digdag.spi.TaskExecutionException;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.spi.TemplateEngine;
import io.digdag.util.BaseOperator;
import io.digdag.util.ConfigContext;
import io.digdag.util.ConfigScope;
import io.digdag.util.ConfigSelector;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Authenticator;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MailOperatorFactory
        implements OperatorFactory
{
    private static Logger logger = LoggerFactory.getLogger(MailOperatorFactory.class);

    private final TemplateEngine templateEngine;
    private final MailDefaults mailDefaults;
    private final Optional<SmtpConfig> systemSmtpConfig;

    @Inject
    public MailOperatorFactory(TemplateEngine templateEngine, Config systemConfig)
    {
        this.templateEngine = templateEngine;
        this.systemSmtpConfig = systemSmtpConfig(systemConfig);
        this.mailDefaults = ImmutableMailDefaults.builder()
                .from(systemConfig.getOptional("config.mail.from", String.class))
                .subject(systemConfig.getOptional("config.mail.subject", String.class))
                .build();
    }

    public String getType()
    {
        return "mail";
    }

    @Override
    public ConfigSelector getSecretAccessList()
    {
        return ConfigSelector.builderOfScope("mail")
            .addSecretAccess("host", "port", "tls", "ssl", "username")
            .addSecretOnlyAccess("password")
            .build();
    }

    @Override
    public Operator newOperator(OperatorContext context)
    {
        return new MailOperator(context);
    }

    @Value.Immutable
    @Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
    public interface AttachConfig
    {
        String getPath();
        String getContentType();
        String getFileName();
    }

    @Value.Immutable
    @Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
    interface SmtpConfig
    {
        String host();
        int port();
        boolean startTls();
        boolean ssl();
        boolean debug();
        Optional<String> username();
        Optional<String> password();
    }

    @Value.Immutable
    @Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
    interface MailDefaults
    {
        Optional<String> subject();
        Optional<String> from();
    }

    private class MailOperator
            extends BaseOperator
    {
        public MailOperator(OperatorContext context)
        {
            super(context);
        }

        @Override
        public TaskResult runTask()
        {
            SecretProvider secrets = context.getSecrets().getSecrets("mail");

            ConfigScope params = ConfigContext.of(context).configScope(getSecretAccessList());

            String body = workspace.templateCommand(templateEngine, params.getTemplateParams(), "body", UTF_8);
            String subject = params.getOptional("subject", String.class).or(mailDefaults.subject()).or(() -> params.get("subject", String.class));

            List<String> toList;
            try {
                toList = params.getScopedConfig().getList("to", String.class);
            }
            catch (ConfigException ex) {
                toList = ImmutableList.of(params.get("to", String.class));
            }

            boolean isHtml = params.getOptional("html", boolean.class).or(false);

            MimeMessage msg = new MimeMessage(createSession(secrets, params));

            try {
                String from = params.getOptional("from", String.class).or(mailDefaults.from()).or(() -> params.get("from", String.class));
                msg.setFrom(newAddress(from));
                msg.setSender(newAddress(from));

                msg.setRecipients(RecipientType.TO,
                        toList.stream()
                                .map(it -> newAddress(it))
                                .toArray(InternetAddress[]::new));

                msg.setSubject(subject);

                List<AttachConfig> attachFiles = attachConfigs(params);
                if (attachFiles.isEmpty()) {
                    msg.setText(body, "utf-8", isHtml ? "html" : "plain");
                }
                else {
                    MimeMultipart multipart = new MimeMultipart();

                    MimeBodyPart textPart = new MimeBodyPart();
                    textPart.setText(body, "utf-8", isHtml ? "html" : "plain");
                    multipart.addBodyPart(textPart);

                    for (AttachConfig attachFile : attachFiles) {
                        MimeBodyPart part = new MimeBodyPart();
                        part.attachFile(workspace.getFile(attachFile.getPath()), attachFile.getContentType(), null);
                        part.setFileName(attachFile.getFileName());
                        multipart.addBodyPart(part);
                    }

                    msg.setContent(multipart);
                }

                Transport.send(msg);
            }
            catch (MessagingException | IOException ex) {
                throw new TaskExecutionException(ex, TaskExecutionException.buildExceptionErrorConfig(ex));
            }

            return TaskResult.empty(request);
        }

        private List<AttachConfig> attachConfigs(ConfigScope params)
        {
            return params.getScopedConfig().getListOrEmpty("attach_files", Config.class)
                    .stream()
                    .map((a) -> {
                        String path = a.get("path", String.class);
                        return ImmutableAttachConfig.builder()
                                .path(path)
                                .fileName(
                                        a.getOptional("filename", String.class)
                                                .or(path.substring(Math.max(path.lastIndexOf('/'), 0)))
                                )
                                .contentType(
                                        a.getOptional("content_type", String.class)
                                                .or("application/octet-stream")
                                )
                                .build();
                    })
                    .collect(Collectors.toList());
        }

        private Session createSession(SecretProvider secrets, ConfigScope params)
        {
            // Use only _either_ user supplied smtp configuration _or_ system smtp configuration to avoid leaking credentials
            // by e.g. connecting to a user controlled host and handing over user/password in base64 plaintext.
            SmtpConfig smtpConfig = userSmtpConfig(secrets, params)
                    .or(systemSmtpConfig)
                    .orNull();

            if (smtpConfig == null) {
                throw new TaskExecutionException("Missing SMTP configuration", ConfigElement.empty());
            }

            Properties props = new Properties();
            props.setProperty("mail.smtp.host", smtpConfig.host());
            props.setProperty("mail.smtp.port", String.valueOf(smtpConfig.port()));
            props.put("mail.smtp.starttls.enable", smtpConfig.startTls());
            if (smtpConfig.ssl()) {
                props.put("mail.smtp.socketFactory.port", smtpConfig.port());
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.socketFactory.fallback", "false");
            }
            props.setProperty("mail.debug", String.valueOf(smtpConfig.debug()));
            props.setProperty("mail.smtp.connectiontimeout", "10000");
            props.setProperty("mail.smtp.timeout", "60000");

            Session session;
            Optional<String> username = smtpConfig.username();
            if (username.isPresent()) {
                props.setProperty("mail.smtp.auth", "true");
                String password = smtpConfig.password().or("");
                session = Session.getInstance(props,
                        new Authenticator()
                        {
                            @Override
                            public PasswordAuthentication getPasswordAuthentication()
                            {
                                return new PasswordAuthentication(username.get(), password);
                            }
                        });
            }
            else {
                session = Session.getInstance(props);
            }
            return session;
        }

        private InternetAddress newAddress(String str)
        {
            try {
                return new InternetAddress(str);
            }
            catch (AddressException ex) {
                throw new ConfigException("Invalid address", ex);
            }
        }
    }

    private static Optional<SmtpConfig> systemSmtpConfig(Config systemConfig)
    {
        Optional<String> host = systemConfig.getOptional("config.mail.host", String.class);
        if (!host.isPresent()) {
            return Optional.absent();
        }
        SmtpConfig config = ImmutableSmtpConfig.builder()
                .host(host.get())
                .port(systemConfig.get("config.mail.port", int.class))
                .startTls(systemConfig.getOptional("config.mail.tls", boolean.class).or(true))
                .ssl(systemConfig.getOptional("config.mail.ssl", boolean.class).or(false))
                .debug(systemConfig.getOptional("config.mail.debug", boolean.class).or(false))
                .username(systemConfig.getOptional("config.mail.username", String.class))
                .password(systemConfig.getOptional("config.mail.password", String.class))
                .build();
        return Optional.of(config);
    }

    private static Optional<SmtpConfig> userSmtpConfig(SecretProvider secrets, ConfigScope params)
    {
        Optional<String> userHost = params.getOptional("host", String.class);
        if (!userHost.isPresent()) {
            return Optional.absent();
        }
        Optional<String> deprecatedPassword = params.getOptional("password", String.class);
        if (deprecatedPassword.isPresent()) {
            logger.warn("Unsecure 'password' parameter is deprecated.");
        }
        SmtpConfig config = ImmutableSmtpConfig.builder()
                .host(userHost.get())
                .port(params.get("port", int.class))
                .startTls(params.getOptional("tls", boolean.class).or(true))
                .ssl(params.getOptional("ssl", boolean.class).or(false))
                .debug(params.getOptional("debug", boolean.class).or(false))
                .username(params.getOptional("username", String.class))
                .password(params.getOptional("password", String.class))
                .build();
        return Optional.of(config);
    }
}
