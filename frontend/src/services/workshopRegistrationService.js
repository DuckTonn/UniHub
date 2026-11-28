import axiosClient from '../api/axiosClient';

const WORKSHOP_API = '/workshops';

const workshopRegistrationService = {
  /**
   * Register for a workshop (free or paid).
   * Returns RegistrationResponse with paidFlow, qrCode, idempotencyKey, amount.
   */
  register: async (workshopId) => {
    const response = await axiosClient.post(`${WORKSHOP_API}/${workshopId}/register`);
    return response;
  },

  /**
   * Process payment for a paid workshop.
   * Idempotency-Key header must be unique per payment attempt.
   */
  processPayment: async (workshopId, idempotencyKey) => {
    const response = await axiosClient.post(
      `${WORKSHOP_API}/${workshopId}/pay`,
      {},
      {
        headers: {
          'Idempotency-Key': idempotencyKey,
        },
      }
    );
    if (response?.checkoutStatus === 'OPEN') {
      const target = new URL(response.checkoutUrl);
      if (target.origin !== 'https://checkout.stripe.com' || target.username || target.password) {
        throw new Error('Sandbox checkout address is invalid.');
      }
      window.location.assign(target.href);
    }
    return response;
  },

  /**
   * Cancel a pending registration
   */
  cancelRegistration: async (workshopId) => {
    const response = await axiosClient.post(`${WORKSHOP_API}/${workshopId}/cancel-registration`);
    return response;
  },
};

export default workshopRegistrationService;
